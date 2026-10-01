package com.example.wallet.domain;

import java.util.UUID;

/** One leg of a journal. Amount is always positive kobo; direction carries the sign. */
public record PostingLine(UUID accountId, Direction direction, long amount, LineType lineType) {}
