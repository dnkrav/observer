package lsfusion.solutions.observer;

import java.io.*;

public class Itemail implements Serializable {
    // Tutorial notes on serialization: https://www.baeldung.com/java-serialization

    public transient String fromLine;
    public transient String addr;
    public transient String date;

    // One of multiline strings handling options out there: https://mkyong.com/java/java-multi-line-string-text-blocks/
    private transient StringWriter messageStrings;
    private transient PrintWriter messagePrint;

    public Itemail() {
        messageStrings = new StringWriter();
        messagePrint = new PrintWriter(messageStrings);
    }

    public Itemail(FromLine from) {
        messageStrings = new StringWriter();
        messagePrint = new PrintWriter(messageStrings);

        // Copy-constructor instead of clonning of the FromLine object
        fromLine = from.line;
        addr = from.addr;
        date = from.date;
        messagePrint.println(fromLine);
    }

    public void appendMessageLine(String line) {
        messagePrint.println(line);
    }

    public String message() {
        return messageStrings.toString();
    }

    private void writeObject(ObjectOutputStream objectOutputStream)
            throws IOException {
        objectOutputStream.writeObject(message());
    }

    private void readObject(ObjectInputStream objectInputStream)
            throws ClassNotFoundException, IOException {
        objectInputStream.defaultReadObject();
        // @ToDo deserialization
    }
}
