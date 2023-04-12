package me.hackerguardian.main.Recording.filesys.saving;
import me.hackerguardian.main.hackerguardian;
import me.hackerguardian.main.Recording.Replay;
import me.hackerguardian.main.Recording.data.ReplayData;
import me.hackerguardian.main.utils.recordinghandler.LogUtils;
import me.hackerguardian.main.utils.recordinghandler.fetcher.Acceptor;
import me.hackerguardian.main.utils.recordinghandler.fetcher.Consumer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
public class DefaultReplaySaver implements IReplaySaver {

    public final static File DIR = new File(hackerguardian.getInstance().getDataFolder() + "/replays/");
    private boolean reformatting;

    private ExecutorService pool = Executors.newCachedThreadPool();

    @Override
    public void saveReplay(Replay replay) {

        if(!DIR.exists()) DIR.mkdirs();

        File file = new File(DIR, replay.getId() + ".replay");

        File file2 = new File(DIR, replay.getId() + ".txt"); //testing


        try {
            if (!file.exists()) file.createNewFile();
            if (!file2.exists()) file2.createNewFile(); //testing

            FileOutputStream fileOut = new FileOutputStream(file);
            GZIPOutputStream gOut = new GZIPOutputStream(fileOut);
            ObjectOutputStream objectOut = new ObjectOutputStream(gOut);

            FileOutputStream fileOut2 = new FileOutputStream(file2); //testing
            GZIPOutputStream gOut2 = new GZIPOutputStream(fileOut2); //testing
            ObjectOutputStream objectOut2 = new ObjectOutputStream(gOut2); //testing

            objectOut.writeObject(replay.getData());
            objectOut.flush();

            objectOut2.writeObject(replay.getData()); //testing
            objectOut2.flush(); //testing

            gOut.close();
            fileOut.close();
            objectOut.close();

            gOut2.close(); //testing
            fileOut2.close(); //testing
            objectOut2.close(); //testing

        } catch (Exception e) {
            e.printStackTrace();
        }


    }


    @Override
    public void loadReplay(String replayName, Consumer<Replay> consumer) {

        this.pool.execute(new Acceptor<Replay>(consumer) {

            @Override
            public Replay getValue() {
                try {

                    File file = new File(DIR, replayName + ".replay");

                    FileInputStream fileIn = new FileInputStream(file);
                    GZIPInputStream gIn = new GZIPInputStream(fileIn);
                    ObjectInputStream objectIn = new ObjectInputStream(gIn);

                    ReplayData data = (ReplayData) objectIn.readObject();

                    objectIn.close();
                    gIn.close();
                    fileIn.close();


                    return new Replay(replayName, data);

                } catch (Exception e) {
                    if(!reformatting) e.printStackTrace();
                }

                return null;
            }
        });
    }

    @Override
    public boolean replayExists(String replayName) {
        File file = new File(DIR, replayName + ".replay");

        return file.exists();
    }

    @Override
    public void deleteReplay(String replayName) {
        File file = new File(DIR, replayName + ".replay");

        if (file.exists()) file.delete();
    }

    public void reformatAll() {
        this.reformatting = true;
        if (DIR.exists()) {
            Arrays.asList(DIR.listFiles()).stream()
                    .filter(file -> (file.isFile() && file.getName().endsWith(".replay")))
                    .map(File::getName)
                    .collect(Collectors.toList())
                    .forEach(file -> reformat(file.replaceAll("\\.replay", "")));
        }

        this.reformatting = false;
    }

    private void reformat(String replayName) {
        loadReplay(replayName, new Consumer<Replay>() {

            @Override
            public void accept(Replay old) {

                if (old == null) {
                    LogUtils.log("Reformatting: " + replayName);

                    try {
                        File file = new File(DIR, replayName + ".replay");

                        FileInputStream fileIn = new FileInputStream(file);
                        ObjectInputStream objectIn = new ObjectInputStream(fileIn);

                        ReplayData data = (ReplayData) objectIn.readObject();

                        objectIn.close();
                        fileIn.close();

                        deleteReplay(replayName);
                        saveReplay(new Replay(replayName, data));

                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                }
            }
        });
    }


    @Override
    public List<String> getReplays() {
        List<String> files = new ArrayList<String>();

        if (DIR.exists()) {
            for (File file : Arrays.asList(DIR.listFiles())) {
                if (file.isFile() && file.getName().endsWith(".replay")) {
                    files.add(file.getName().replaceAll("\\.replay", ""));
                }
            }
        }
        return files;
    }

}
