package me.hackerguardian.main.Recording.API;


import me.hackerguardian.main.Recording.data.ActionData;
import me.hackerguardian.main.Recording.data.types.PacketData;
import me.hackerguardian.main.Recording.replaying.Replayer;

import java.util.List;

public interface IReplayHook {

    List<PacketData> onRecord(String playerName);

    void onPlay(ActionData data, Replayer replayer);
}
