package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"sync"
	"sync/atomic"
	"time"
)

const baseURL = "https://wallet-service-pt2d.onrender.com"

type TransferRequest struct {
	WalletFromID   int64  `json:"walletFromId"`
	WalletToID     int64  `json:"walletToId"`
	AmountPaisa    int64  `json:"amountPaisa"`
	IdempotencyKey string `json:"idempotencyKey"`
}

type TransferResponse struct {
	ID             string `json:"id"`
	WalletFromID   int64  `json:"walletFromId"`
	WalletToID     int64  `json:"walletToId"`
	AmountPaisa    int64  `json:"amountPaisa"`
	Status         string `json:"status"`
	IdempotencyKey string `json:"idempotencyKey"`
}

type WalletResponse struct {
	ID          int64  `json:"id"`
	UserID      int64  `json:"userId"`
	AmountPaisa int64  `json:"amountPaisa"`
	Status      string `json:"status"`
}

var client = &http.Client{
	Timeout: 30 * time.Second,
}

func sendTransfer(req TransferRequest) (int, *TransferResponse, error) {
	body, err := json.Marshal(req)
	if err != nil {
		return 0, nil, err
	}

	httpReq, err := http.NewRequest(
		http.MethodPost,
		baseURL+"/transfers",
		bytes.NewBuffer(body),
	)
	if err != nil {
		return 0, nil, err
	}

	httpReq.Header.Set("Content-Type", "application/json")

	resp, err := client.Do(httpReq)
	if err != nil {
		return 0, nil, err
	}

	defer resp.Body.Close()

	responseBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return resp.StatusCode, nil, err
	}

	if resp.StatusCode >= 400 {
		return resp.StatusCode, nil, fmt.Errorf(
			"HTTP %d: %s",
			resp.StatusCode,
			string(responseBody),
		)
	}

	var result TransferResponse

	if err := json.Unmarshal(responseBody, &result); err != nil {
		return resp.StatusCode, nil, err
	}

	return resp.StatusCode, &result, nil
}

func getWallet(walletID int64) (*WalletResponse, error) {
	resp, err := client.Get(
		fmt.Sprintf("%s/wallets/%d", baseURL, walletID),
	)
	if err != nil {
		return nil, err
	}

	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}

	if resp.StatusCode >= 400 {
		return nil, fmt.Errorf(
			"HTTP %d: %s",
			resp.StatusCode,
			string(body),
		)
	}

	var wallet WalletResponse

	if err := json.Unmarshal(body, &wallet); err != nil {
		return nil, err
	}

	return &wallet, nil
}

func idempotencyTest(totalRequests int) {
	fmt.Println("========================================")
	fmt.Println(" IDPOTENCY CONCURRENCY TEST")
	fmt.Println("========================================")
	fmt.Printf("Requests: %d\n\n", totalRequests)

	request := TransferRequest{
		WalletFromID:   1001,
		WalletToID:     1002,
		AmountPaisa:    100,
		IdempotencyKey: fmt.Sprintf("stress-idempotency-%d", time.Now().UnixNano()),
	}

	var wg sync.WaitGroup

	results := make(chan *TransferResponse, totalRequests)

	var success int64
	var failures int64

	start := time.Now()

	wg.Add(totalRequests)

	for i := 0; i < totalRequests; i++ {
		go func() {
			defer wg.Done()

			status, result, err := sendTransfer(request)

			if err != nil {
				atomic.AddInt64(&failures, 1)
				return
			}

			if status == http.StatusOK || status == http.StatusCreated {
				atomic.AddInt64(&success, 1)
				results <- result
			}
		}()
	}

	wg.Wait()
	close(results)

	elapsed := time.Since(start)

	uniqueIDs := make(map[string]bool)

	for result := range results {
		uniqueIDs[result.ID] = true
	}

	fmt.Printf("Elapsed:          %v\n", elapsed)
	fmt.Printf("Successful:       %d\n", success)
	fmt.Printf("Failed:           %d\n", failures)
	fmt.Printf("Unique transfer IDs: %d\n", len(uniqueIDs))

	fmt.Println()

	if len(uniqueIDs) == 1 {
		fmt.Println("Idempotency: PASS")
	} else {
		fmt.Println("Idempotency: FAIL")
	}

	walletA, err := getWallet(1001)
	if err != nil {
		fmt.Println("Failed reading wallet A:", err)
		return
	}

	walletB, err := getWallet(1002)
	if err != nil {
		fmt.Println("Failed reading wallet B:", err)
		return
	}

	fmt.Printf("\nWallet 1001 balance: %d\n", walletA.AmountPaisa)
	fmt.Printf("Wallet 1002 balance: %d\n", walletB.AmountPaisa)

	fmt.Println("\nExpected:")
	fmt.Println("Wallet 1001 should decrease by exactly 100")
	fmt.Println("Wallet 1002 should increase by exactly 100")
}

func overdraftTest(totalRequests int) {
	fmt.Println("========================================")
	fmt.Println(" OVERDRAFT / CONCURRENCY TEST")
	fmt.Println("========================================")
	fmt.Printf("Requests: %d\n", totalRequests)
	fmt.Println("Transfer: Wallet 1003 -> Wallet 1004")
	fmt.Println("Amount:   100 paise")
	fmt.Println()

	walletA, err := getWallet(1003)
	if err != nil {
		fmt.Println("Failed reading wallet A:", err)
		return
	}

	walletB, err := getWallet(1004)
	if err != nil {
		fmt.Println("Failed reading wallet B:", err)
		return
	}

	initialA := walletA.AmountPaisa
	initialB := walletB.AmountPaisa
	initialTotal := initialA + initialB

	fmt.Printf("Initial A:     %d\n", initialA)
	fmt.Printf("Initial B:     %d\n", initialB)
	fmt.Printf("Initial total: %d\n\n", initialTotal)

	var wg sync.WaitGroup

	var completed int64
	var declined int64
	var failed int64

	wg.Add(totalRequests)

	for i := 0; i < totalRequests; i++ {
		go func(i int) {
			defer wg.Done()

			request := TransferRequest{
				WalletFromID: 1003,
				WalletToID:   1004,
				AmountPaisa:  100,
				IdempotencyKey: fmt.Sprintf(
					"stress-overdraft-%d-%d",
					time.Now().UnixNano(),
					i,
				),
			}

			status, _, err := sendTransfer(request)

			if err != nil {
				atomic.AddInt64(&failed, 1)
				return
			}

			switch status {
			case http.StatusOK, http.StatusCreated:
				atomic.AddInt64(&completed, 1)

			case http.StatusConflict,
				http.StatusBadRequest,
				http.StatusUnprocessableEntity:
				atomic.AddInt64(&declined, 1)
			}
		}(i)
	}

	wg.Wait()

	elapsed := time.Since(time.Now())

	walletA, err = getWallet(1003)
	if err != nil {
		fmt.Println("Failed reading final wallet A:", err)
		return
	}

	walletB, err = getWallet(1004)
	if err != nil {
		fmt.Println("Failed reading final wallet B:", err)
		return
	}

	finalA := walletA.AmountPaisa
	finalB := walletB.AmountPaisa
	finalTotal := finalA + finalB

	expectedA := initialA - completed*100
	expectedB := initialB + completed*100

	fmt.Printf("Elapsed:       %v\n", elapsed)
	fmt.Printf("Completed:     %d\n", completed)
	fmt.Printf("Declined:      %d\n", declined)
	fmt.Printf("Failed:        %d\n\n", failed)

	fmt.Println()
}

func main() {

	if len(os.Args) < 3 {
		fmt.Println("Usage:")
		fmt.Println("  go run . idempotency 10000")
		fmt.Println("  go run . overdraft 10000")
		return
	}

	test := os.Args[1]

	var totalRequests int

	if _, err := fmt.Sscanf(os.Args[2], "%d", &totalRequests); err != nil {
		fmt.Println("Invalid request count")
		return
	}

	switch test {

	case "idempotency":
		idempotencyTest(totalRequests)

	case "overdraft":
		overdraftTest(totalRequests)

	default:
		fmt.Println("Unknown test:", test)
	}
}
