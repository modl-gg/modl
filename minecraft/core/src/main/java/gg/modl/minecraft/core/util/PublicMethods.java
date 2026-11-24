package gg.modl.minecraft.core.util;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class PublicMethods {
    private PublicMethods() {
    }

    public static List<Method> withoutParameters(Class<?> type, String name) {
        List<Method> matches = new ArrayList<>();
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 0) matches.add(method);
        }
        return matches;
    }
}
