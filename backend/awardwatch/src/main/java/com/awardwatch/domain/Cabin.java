package com.awardwatch.domain;

public enum Cabin {
    ECONOMY('Y'),
    PREMIUM_ECONOMY('W'),
    BUSINESS('J'),
    FIRST('F');

    private final char code;

    Cabin(char code) {
        this.code = code;
    }

    // IATA-style cabin letter, e.g. 'J' for business.
    public char code() {
        return code;
    }
}
