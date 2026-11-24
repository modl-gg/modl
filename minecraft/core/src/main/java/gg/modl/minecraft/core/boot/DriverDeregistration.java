package gg.modl.minecraft.core.boot;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DriverDeregistration implements Runnable {
    @Override
    public void run() {
        ClassLoader own = DriverDeregistration.class.getClassLoader();
        List<Driver> owned = new ArrayList<>();
        for (Driver driver : Collections.list(DriverManager.getDrivers())) {
            if (driver.getClass().getClassLoader() == own) owned.add(driver);
        }
        for (Driver driver : owned) deregister(driver);
    }

    private static void deregister(Driver driver) {
        try {
            DriverManager.deregisterDriver(driver);
        } catch (SQLException | SecurityException refused) {
            throw new IllegalStateException("The driver " + driver.getClass().getName() + " stays registered", refused);
        }
    }
}
