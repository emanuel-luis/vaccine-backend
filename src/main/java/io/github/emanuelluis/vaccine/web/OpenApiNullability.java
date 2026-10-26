package io.github.emanuelluis.vaccine.web;

import com.fasterxml.jackson.databind.JavaType;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Makes the OpenAPI schema of every record say what the Java type says (JSpecify): a component is
 * required and non-null unless annotated {@link Nullable}. A nullable component is left out of
 * {@code required} and accepts {@code null}: scalars get {@code "type": [<type>, "null"]}, references
 * become {@code oneOf: [{$ref}, {type: null}]}. Jackson writes every record component, nulls included,
 * so "required" means "always present" in responses.
 *
 * <p>Runs as a springdoc model converter because JSpecify annotations are type-use only and never reach
 * property customizers.
 */
@Component
public class OpenApiNullability implements ModelConverter {

    @Override
    public boolean isOpenapi31() {
        return true;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public Schema resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
        Schema resolved = chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
        if (resolved == null || type.getType() == null) {
            return resolved;
        }
        JavaType javaType = Json.mapper().constructType(type.getType());
        if (!javaType.isRecordType()) {
            return resolved;
        }
        String ref = resolved.get$ref();
        Schema model = ref == null ? resolved : context.getDefinedModels().get(ref.substring(ref.lastIndexOf('/') + 1));
        if (model != null && model.getProperties() != null) {
            apply(javaType.getRawClass(), model);
        }
        return resolved;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void apply(Class<?> record, Schema model) {
        Map<String, Schema> properties = model.getProperties();
        List<String> required = new ArrayList<>();
        for (RecordComponent component : record.getRecordComponents()) {
            Schema property = properties.get(component.getName());
            if (property == null) {
                continue;
            }
            if (component.getAnnotatedType().isAnnotationPresent(Nullable.class)) {
                properties.put(component.getName(), nullable(property));
            } else {
                required.add(component.getName());
            }
        }
        model.setRequired(required.isEmpty() ? null : required);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Schema nullable(Schema property) {
        if (property.get$ref() != null) {
            return new JsonSchema().oneOf(List.of(property, new JsonSchema().types(Set.of("null"))));
        }
        Set<String> types = new LinkedHashSet<>();
        if (property.getTypes() != null) {
            types.addAll(property.getTypes());
        } else if (property.getType() != null) {
            types.add(property.getType());
        }
        if (types.isEmpty()) {
            return property;
        }
        types.add("null");
        property.setType(null);
        property.setTypes(types);
        return property;
    }
}
