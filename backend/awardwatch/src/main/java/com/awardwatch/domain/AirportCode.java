package com.awardwatch.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A 3-letter IATA airport code, e.g. {@code "ATL"}.
 *
 * Exists so that "this string is a valid airport code" is proved once, at construction,
 * rather than re-checked by every record that holds one. An {@code AirportCode} instance
 * cannot be invalid, so no downstream caller has to defend against a two-letter code, a
 * lower-case one, or an empty string.
 *
 * Codes are canonicalized to upper case, which makes {@code equals} a correct identity
 * test: {@code "atl"} from a query string and {@code "ATL"} from a source response are
 * the same airport and compare equal. Nothing else in the model may compare airport
 * codes as raw strings.
 *
 * This is an airport, not a city or a metro area. {@code LHR}, {@code LGW} and
 * {@code LCY} are three codes, and a search for one does not cover the others.
 *
 * @param value the code itself, canonicalized to upper case on construction and
 *              guaranteed to match {@code [A-Z]{3}}
 */
public record AirportCode(String value) {

    private static final Pattern IATA = Pattern.compile("[A-Z]{3}");

    /**
     * Canonicalizes the code and rejects anything that is not three letters, so that an
     * invalid code cannot exist to reach a source, a diff, or the append-only history.
     *
     * @throws NullPointerException     if {@code value} is null
     * @throws IllegalArgumentException if {@code value} is not three letters. The message
     *                                  reports the input as given, not as canonicalized,
     *                                  so a bad code is recognizable in a log
     */
    public AirportCode {

        Objects.requireNonNull(value, "airport code cannot be null");

        String canonical = value.toUpperCase(Locale.ROOT);

        if (!IATA.matcher(canonical).matches()) {
            throw new IllegalArgumentException("airport code must be three letters, was: " + value);
        }

        value = canonical;

    }

    /**
     * The bare code, so that logs, keys and {@link Route#toString()} read as
     * {@code ATL} rather than {@code AirportCode[value=ATL]}.
     *
     * @return the canonicalized 3-letter code
     */
    @Override
    public String toString() {
        return value;
    }

}