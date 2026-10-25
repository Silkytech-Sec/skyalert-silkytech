package app.skyalert.core;

public interface Logger {
    void log(String message);

    Logger NONE = m -> { };
}
