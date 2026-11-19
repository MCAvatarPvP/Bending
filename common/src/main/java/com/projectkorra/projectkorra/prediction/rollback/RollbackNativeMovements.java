package com.projectkorra.projectkorra.prediction.rollback;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerValues.*;

/** Transfers native swept block contacts across the freeze, including the original axis-dependent motion. */
public final class RollbackNativeMovements<V> {
    private final Field pending, checked;
    private final Class<V> vector;
    private final Method from, to, axis;
    private final Constructor<?> construct;
    private final Function<V,Vector> encode;
    private final Function<Vector,V> decode;

    public RollbackNativeMovements(Class<?> entity, String pendingName, String checkedName, Class<V> vector,
            Function<V,Vector> encode, Function<Vector,V> decode) {
        this.vector = vector; this.encode = encode; this.decode = decode;
        try {
            pending = entity.getDeclaredField(pendingName); checked = entity.getDeclaredField(checkedName);
            pending.setAccessible(true); checked.setAccessible(true);
            var type = (Class<?>) ((ParameterizedType)pending.getGenericType()).getActualTypeArguments()[0];
            var components = type.getRecordComponents();
            if (components.length != 3 || components[0].getType() != vector || components[1].getType() != vector || components[2].getType() != Optional.class)
                throw new IllegalStateException("Unexpected native movement schema");
            from = components[0].getAccessor(); to = components[1].getAccessor(); axis = components[2].getAccessor();
            from.setAccessible(true); to.setAccessible(true); axis.setAccessible(true);
            construct = type.getDeclaredConstructor(vector, vector, Optional.class); construct.setAccessible(true);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Native movement schema", failure); }
    }

    public record History(Movements pending, Movements checked) {
        public void export(Map<String,Cell> fields) {
            if (!pending.steps().isEmpty()) fields.put("entity.pendingMovements", new Cell(Kind.MOVEMENTS, pending));
            if (!checked.steps().isEmpty()) fields.put("entity.checkedMovements", new Cell(Kind.MOVEMENTS, checked));
        }
        public static History read(Map<String,Cell> fields) {
            return new History(remove(fields, "entity.pendingMovements"), remove(fields, "entity.checkedMovements"));
        }
        private static Movements remove(Map<String,Cell> fields, String name) {
            var cell = fields.remove(name);
            if (cell == null) return new Movements(List.of());
            if (cell.kind() != Kind.MOVEMENTS || !(cell.value() instanceof Movements moves)) throw new IllegalArgumentException(name);
            return moves;
        }
    }

    public History capture(Object body) {
        try { return new History(capture((Collection<?>)pending.get(body)), capture((Collection<?>)checked.get(body))); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Read native movement", failure); }
    }
    private Movements capture(Collection<?> rows) throws ReflectiveOperationException {
        var moves = new ArrayList<Movement>(rows.size());
        for (var row : rows) {
            var original = (Optional<?>)axis.invoke(row);
            moves.add(new Movement(encode.apply(vector.cast(from.invoke(row))), encode.apply(vector.cast(to.invoke(row))),
                    original.map(vector::cast).map(encode).orElse(null)));
        }
        return new Movements(moves);
    }

    @SuppressWarnings("unchecked")
    public void apply(Object body, History history) {
        try {
            var next = nativeRows(history.pending()); var previous = nativeRows(history.checked());
            var queue = (Collection<Object>)pending.get(body); var trace = (Collection<Object>)checked.get(body);
            queue.clear(); queue.addAll(next); trace.clear(); trace.addAll(previous);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Restore native movement", failure); }
    }
    private List<Object> nativeRows(Movements moves) throws ReflectiveOperationException {
        var rows = new ArrayList<>(moves.steps().size());
        for (var step : moves.steps()) rows.add(construct.newInstance(decode.apply(step.from()), decode.apply(step.to()),
                Optional.ofNullable(step.axisMovement()).map(decode)));
        return rows;
    }
}
