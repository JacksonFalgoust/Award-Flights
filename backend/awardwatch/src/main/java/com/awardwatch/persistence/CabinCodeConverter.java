package com.awardwatch.persistence;

import com.awardwatch.domain.Cabin;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;

@Converter
public class CabinCodeConverter implements AttributeConverter<Cabin, String> {

    @Override
    public String convertToDatabaseColumn(Cabin cabin) {
        return cabin == null ? null : Character.toString(cabin.code());
    }

    @Override
    public Cabin convertToEntityAttribute(String code) {
        if (code == null) {
            return null;
        }

        return Arrays.stream(Cabin.values())
            .filter(cabin -> Character.toString(cabin.code()).equals(code))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("unknown cabin code: " + code));
    }
}
