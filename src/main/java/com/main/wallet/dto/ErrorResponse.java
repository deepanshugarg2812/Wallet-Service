package com.main.wallet.dto;

import java.time.ZonedDateTime;

public record ErrorResponse(
        int status,
        String message,
        ZonedDateTime timestamp
) {}
