package me.hackerguardian.main.Recording.data.types;

public class ChatData extends PacketData {


    /**
     *
     */
    private static final long serialVersionUID = 6849586468365004854L;

    private String message;

    public ChatData(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}
