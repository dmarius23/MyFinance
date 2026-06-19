package ro.myfinance.settings.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import java.util.List;

/** Maps a List&lt;String&gt; to/from a comma-separated column (used for treasury tax-type codes). */
@Converter
public class StringListConverter implements AttributeConverter<List<String>, String> {

    @Override
    public String convertToDatabaseColumn(List<String> list) {
        return list == null ? "" : String.join(",", list);
    }

    @Override
    public List<String> convertToEntityAttribute(String s) {
        if (s == null || s.isBlank()) {
            return List.of();
        }
        return Arrays.stream(s.split(",")).map(String::trim).filter(v -> !v.isEmpty()).toList();
    }
}
