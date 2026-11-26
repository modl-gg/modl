package gg.modl.minecraft.core.boot.drivers;

import java.sql.Driver;
import java.sql.DriverManager;
import java.util.Collections;
import java.util.function.IntSupplier;

public final class RegisteredDrivers implements IntSupplier {
    @Override
    public int getAsInt() {
        ClassLoader own = RegisteredDrivers.class.getClassLoader();
        int count = 0;
        for (Driver driver : Collections.list(DriverManager.getDrivers())) {
            if (driver.getClass().getClassLoader() == own) count++;
        }
        return count;
    }
}
