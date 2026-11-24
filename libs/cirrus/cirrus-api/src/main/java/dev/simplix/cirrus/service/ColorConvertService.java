package dev.simplix.cirrus.service;

import java.awt.Color;

public interface ColorConvertService {

    String colorToString(Color color);

    Color stringToColor(String hexColor);

}
