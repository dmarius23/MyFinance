package ro.myfinance.taxpayments.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Maps a {@code List<UUID>} to a comma-separated text column (the declarations an email covered). */
@Converter
public class UuidListConverter implements AttributeConverter<List<UUID>, String> {

    @Override
    public String convertToDatabaseColumn(List<UUID> ids) {
        return (ids == null || ids.isEmpty()) ? "" : ids.stream().map(UUID::toString).collect(Collectors.joining(","));
    }

    @Override
    public List<UUID> convertToEntityAttribute(String s) {
        if (s == null || s.isBlank()) {
            return List.of();
        }
        return Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty())
                .map(UUID::fromString).collect(Collectors.toList());
    }
}
