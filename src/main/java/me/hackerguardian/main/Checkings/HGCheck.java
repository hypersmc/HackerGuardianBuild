package me.hackerguardian.main.Checkings;

import org.bukkit.event.Event;

import javax.naming.OperationNotSupportedException;

/**
 * @author JumpWatch on 28-02-2024
 * @Project HackerGuardian
 * v1.0.0
 */
public abstract class HGCheck {
    public abstract String getName();

    public abstract String getEventCall();

    public abstract HGCheckResult performCheck(User u, Event e) throws OperationNotSupportedException;

    public HGCheckResult performCheck(User u) throws OperationNotSupportedException {
        return performCheck(u, null);
    }

    public String getSecondaryEventCall() {
        return "";
    }
}
