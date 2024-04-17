package me.hackerguardian.main.Checkings;

import org.bukkit.entity.Player;

/**
 * @author JumpWatch on 28-02-2024
 * @Project HackerGuardian
 * v1.0.0
 */
public class HGChecksHandler {

    public boolean addSuspicion(Player p, String detector, String description) {
//        MySQL sql = new MySQL();
//        if (!reports.containsKey(p))
//            reports.put(p, new HashMap<Long, String>());
//
//        if (EXEMPTHANDLER.isExempt(p)) {
//            return false;
//        }
//        this.getUser(p).updateLastOffense();
//
//        int ping = 0;
//
//        try {
//            Object entityPlayer = p.getClass().getMethod("getHandle").invoke(p);
//            ping = (int) entityPlayer.getClass().getField("ping").get(entityPlayer);
//        } catch (Exception e) {
//            if (HackerGuardian.getInstance().getConfig().getBoolean("debug")) e.printStackTrace();
//        }
//        if (ping > 0)
//            ping = ping / 2;
//
//        int Count = 0;
//        reports.get(p).put((System.currentTimeMillis() + (System.currentTimeMillis() * 10)), detector);
//
//        for (String v : reports.get(p).values()) {
//            if (v.equalsIgnoreCase(detector)) {
//                Count++;
//            }
//        }
//        if (Count <= 2) {
//            return false;
//        }
//        if (p.getVehicle() == null) {
//            EXEMPTHANDLER.addExemptionBlock(p, 100);
//            if (detector.equalsIgnoreCase("Anti-Cactus") || detector.equalsIgnoreCase("Anti-BerryBush")) {
//                p.damage(0.5D);
//            } else if (detector.equalsIgnoreCase("WaterWalk")) {
//                p.teleport(p.getLocation().add(0, -0.5, 0));
//            } else if (detector.equalsIgnoreCase("Criticals") || detector.equalsIgnoreCase("XRay") || detector.equalsIgnoreCase("Timer1")) {
//            } else {
//                p.teleport(this.getUser(p).LastRegularLocation());
//            }
//        }
//        playerdata.OC++;
//        if (!playerdata.CC.containsKey(detector)) {
//            playerdata.CC.put(detector, 1);
//        } else {
//            playerdata.CC.put(detector, playerdata.CC.get(detector) + 1);
//        }
//
//        if (Tps.getTPS() <= HackerGuardian.getInstance().getConfig().getLong("Settings.mintps") || ping >= 125) {
//            return false;
//        }
//        Integer c = 1;
//        if (playerdata.MS.containsKey(p.getName() + " - " + p.getUniqueId()))
//            c = playerdata.MS.get(p.getName() + " - " + p.getUniqueId());
//
//        c++;
//        if (!playerdata.UC.containsKey(p.getUniqueId()))
//            playerdata.UC.put(p.getUniqueId(), new HashMap<String, Integer>());
//
//        UUID uuid = p.getUniqueId();
//        if (!playerdata.UC.get(uuid).containsKey(detector)) {
//            playerdata.UC.get(uuid).put(detector, 1);
//        } else {
//            playerdata.UC.get(uuid).put(detector, playerdata.UC.get(uuid).get(detector) + 1);
//        }
//        playerdata.MS.put(p.getName() + " - " + p.getUniqueId(), c);
//
//
//
//
//        if (updateDatabase(p, detector, Count, description)) {
//            return false;
//        }
//
//        String m = SUSPICION_ALERT;
//
//        m = m.replaceAll("\\[VARIABLE_COLOR\\]", playertext(prefix));
//        m = m.replaceAll("\\[DISPLAYNAME\\]", p.getDisplayName());
//        m = m.replaceAll("\\[USERNAME\\]", p.getName());
//        m = m.replaceAll("\\[NAME\\]", p.getName());
//        m = m.replaceAll("\\[UUID\\]", p.getUniqueId().toString());
//        m = m.replaceAll("\\[RESDESC\\]", description);
//        m = m.replaceAll("\\[SUSPICION\\]", detector);
//        m = m.replaceAll("\\[COUNT\\]", Count - 2 + "");
//        m = m.replaceAll("\\[PING\\]", ping + "" );
//        m = m.replaceAll("\\[TPS\\]", Tps.getNiceTPS() + "");
//        m = m.replaceAll("\\[X\\]", UtilMath.trim(2, p.getLocation().getX()) + "");
//        m = m.replaceAll("\\[Y\\]", UtilMath.trim(2, p.getLocation().getY()) + "");
//        m = m.replaceAll("\\[Z\\]", UtilMath.trim(2, p.getLocation().getZ()) + "");
//        m = m.replaceAll("\\[WORLD\\]", p.getWorld().getName());
//
//        for (Player p2 : getServer().getOnlinePlayers()) {
//            if (p2.hasPermission("*") || p2.hasPermission("hg.notify")) {
//                p2.sendMessage(m);
//            }
//        }
//        if (getConfig().getBoolean("Settings.Addoneachtriggercoung")) {
//            sql.addPlayerTriggers(p.getUniqueId(), detector.replace("'", "\\'"));
//        }

        return true;
    }
}
