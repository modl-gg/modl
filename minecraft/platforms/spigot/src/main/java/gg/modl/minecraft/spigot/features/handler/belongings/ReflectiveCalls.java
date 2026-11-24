package gg.modl.minecraft.spigot.features.handler.belongings;

import gg.modl.minecraft.features.staffmode.custody.BelongingsException;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

final class ReflectiveCalls {
    private ReflectiveCalls() {
    }

    static Object call(Method method, @Nullable Object target, @Nullable Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            throw new IllegalStateException(method.getName() + " failed", failure.getCause());
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException(method.getName() + " is not accessible", failure);
        }
    }

    static Object create(Constructor<?> constructor, @Nullable Object... arguments) {
        try {
            return constructor.newInstance(arguments);
        } catch (InvocationTargetException failure) {
            throw new IllegalStateException(constructor.getName() + " failed", failure.getCause());
        } catch (InstantiationException | IllegalAccessException failure) {
            throw new IllegalStateException(constructor.getName() + " is not usable", failure);
        }
    }

    static BelongingsException failed(String action, Throwable failure) {
        return new BelongingsException("Could not " + action, failure);
    }
}
