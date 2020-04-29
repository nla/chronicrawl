package trickler;

import java.io.IOException;

public class Util {
    public interface IOConsumer<T> {
        void accept(T value) throws IOException;
    }
}
