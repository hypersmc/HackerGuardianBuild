package me.hackerguardian.main.events;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import me.hackerguardian.main.aicore.TrainingData;
import me.hackerguardian.main.hackerguardian;

/**
 * @author JumpWatch on 28-11-2023
 * @Project HackerGuardiane
 * v1.0.0
 */
public class onPackageListener {
    static hackerguardian main = hackerguardian.getInstance();
    private final ProtocolManager protocolManager;
    private static final TrainingData trainingData = new TrainingData(main.calculateTotalInputs(), 1);
    public onPackageListener() {
        this.protocolManager = ProtocolLibrary.getProtocolManager();
        registerPacketListener();
    }

    private void registerPacketListener() {
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.TELEPORT_ACCEPT) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.TILE_NBT_QUERY) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });

        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.DIFFICULTY_CHANGE) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });

        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.CHAT_ACK) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });

        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.CHAT_COMMAND) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.CHAT) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.CHAT_SESSION_UPDATE) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.SETTINGS) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.TAB_COMPLETE) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.ENCHANT_ITEM) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.WINDOW_CLICK) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.CLOSE_WINDOW) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.CUSTOM_PAYLOAD) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.B_EDIT) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.ENTITY_NBT_QUERY) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.USE_ENTITY) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.JIGSAW_GENERATE) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.KEEP_ALIVE) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.DIFFICULTY_LOCK) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.POSITION) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.POSITION_LOOK) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.LOOK) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.GROUND) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.VEHICLE_MOVE) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.BOAT_MOVE) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.PICK_ITEM) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.AUTO_RECIPE) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.ABILITIES) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.BLOCK_DIG) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.ENTITY_ACTION) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.STEER_VEHICLE) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.PONG) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.RECIPE_SETTINGS) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.RECIPE_DISPLAYED) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.ITEM_NAME) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.RESOURCE_PACK_STATUS) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.ADVANCEMENTS) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.TR_SEL) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.BEACON) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.HELD_ITEM_SLOT) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.SET_COMMAND_BLOCK) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.SET_COMMAND_MINECART) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.SET_CREATIVE_SLOT) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.SET_JIGSAW) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.STRUCT) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.UPDATE_SIGN) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.ARM_ANIMATION) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.SPECTATE) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.USE_ITEM) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(main, ListenerPriority.HIGH, PacketType.Play.Client.BLOCK_PLACE) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (main.learning) {

                }
            }
        });

    }
}