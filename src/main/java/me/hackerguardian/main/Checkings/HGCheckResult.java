package me.hackerguardian.main.Checkings;

/**
 * @author JumpWatch on 28-02-2024
 * @Project HackerGuardian
 * v1.0.0
 */
public class HGCheckResult {
    private String name;
    private Boolean pf;
    private String desc;

    public HGCheckResult(String CheckName, Boolean passed, String description) {
        name = CheckName;
        pf = passed;
        this.desc = description;
    }

    public String getDesc() {
        return this.desc;
    }

    public boolean passed() {
        return pf;
    }

    public String getCheckName() {
        return name;
    }
}
