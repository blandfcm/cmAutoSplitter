/*
 * Plugin for automating LiveSplits for Cox Challenge Mode.
 * Based on De0's CoxTimers.
 */

package sky.cmAutoSplitter;

import com.google.inject.Provides;
import net.runelite.api.Point;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GraphicsObjectCreated;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

import java.awt.image.BufferedImage;
import java.io.PrintWriter;
import javax.inject.Inject;

import static sky.cmAutoSplitter.CoxUtil.ICE_DEMON;
import static sky.cmAutoSplitter.CoxUtil.getroom_type;

@PluginDescriptor(name = "CoX Auto splitter", description = "Auto splitter for LiveSplit for cox cm")
public class CoxCMAutoSplitter extends Plugin {

    @Inject
    private Client client;

    @Inject
    private CoxCMAutoSplitterConfig config;

    @Inject
    private ClientToolbar clientToolbar;

    // LiveSplit server
    PrintWriter writer;

    // side panel
    private NavigationButton navButton;
    private CoxCMAutoSplitterPanel panel;

    // for determining raid start
    private int prevRaidState = -1;

    // Room state
    private boolean in_raid;
    private final int[] cryp = new int[16];
    private final int[] cryx = new int[16];
    private final int[] cryy = new int[16];

    // Olm state
    private int olm_phase;

    // Misc state
    private boolean iceout, treecut;

    @Provides
    CoxCMAutoSplitterConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(CoxCMAutoSplitterConfig.class);
    }

    @Subscribe
    public void onClientTick(ClientTick e) {
        if (client.getGameState() != GameState.LOGGED_IN)
            return;

        if (client.getVarbitValue(VarbitID.RAIDS_TIMER) == 0 || !client.getTopLevelWorldView().isInstance()) {
            in_raid = false;
            return;
        }
        if (!in_raid) {
            in_raid = true;
            olm_phase = ~0;
            iceout = false;
            treecut = false;
        }

        int top_level_x = client.getTopLevelWorldView().getBaseX();
        int top_level_y = client.getTopLevelWorldView().getBaseY();
        int top_level_p = client.getTopLevelWorldView().getPlane();
        CollisionData[] collision_maps = client.getTopLevelWorldView().getCollisionMaps();
        if (collision_maps == null) {
            return;
        }

        for (int i = 0; i < 16; i++) {
            if (this.cryp[i] == -1)
                continue;
            int p = cryp[i];
            int x = cryx[i] - top_level_x;
            int y = cryy[i] - top_level_y;
            if (p != top_level_p || x < 0 || x >= 104 || y < 0 || y >= 104) {
                this.cryp[i] = -1;
                continue;
            }
            int flags = collision_maps[p].getFlags()[x][y];
            if ((flags & 0x100) == 0 && !config.regular()) {
                // combat and puzzle rooms
                send_split();
                this.cryp[i] = -1;
            }
        }
    }

    private static final String FL_COMPLETE_MES = "level complete! Duration: </col><col=ff0000>";

    @Subscribe
    public void onChatMessage(ChatMessage e) {
        String mes = e.getMessage();
        if (e.getType() == ChatMessageType.FRIENDSCHATNOTIFICATION && mes.startsWith("<col=ef20ff>")) {
            int duration = mes.indexOf(FL_COMPLETE_MES);
            boolean is_fl_time = duration != -1;

            if (!is_fl_time)
                return;

            send_split();

        } else if (e.getType() == ChatMessageType.GAMEMESSAGE && mes.equals(
                "The Great Olm is giving its all. This is its final stand.")) {
            // head phase
            send_split();
            olm_phase = 99;
        }
    }

    @Subscribe
    public void onGameObjectSpawned(GameObjectSpawned e) {
        GameObject go = e.getGameObject();
        switch (go.getId()) {
            case ObjectID.OLM_HEAD: // Olm spawned
                if (olm_phase < 0) {
                    olm_phase = ~olm_phase;
                }
                break;
            case ObjectID.RAIDS_MEAT_TREE_EMPTY:
                // Muttadile tree placeholder spawned after tree cut
                if (config.splitMuttadileTree() && !treecut && !config.regular()) {
                    send_split();
                    treecut = true;
                }
                break;
            case ObjectID.INVISIBLE_TYPE8_BLOCKING: // shamans/thieving/guardians
            case ObjectID.RAIDS_SKELETALMYSTICS_SYMBOL: // mystics
            case ObjectID.RAIDS_TIGHTROPE_BARRIER: // tightrope
            case ObjectID.RAIDS_LASERCRABS_BIGCRYSTAL_1: // crabs
            case ObjectID.RAIDS_LASERCRABS_BIGCRYSTAL_2:
            case ObjectID.RAIDS_LASERCRABS_BIGCRYSTAL_3:
            case ObjectID.RAIDS_LASERCRABS_BIGCRYSTAL_4:
            case ObjectID.RAIDS_LASERCRABS_BIGCRYSTAL_5:
            case ObjectID.RAIDS_ICEDEMON_SNOW: // ice
            case ObjectID.RAIDS_BLOCKAGE_PURPLE_SMALL: // vasa
            case ObjectID.RAIDS_BLOCKAGE_ORANGE: // tekton/vanguards
            case ObjectID.RAIDS_BLOCKAGE_GREEN: // mutt
            case ObjectID.RAIDS_VESPULA_BOIL_BLOCKING: // vespula
                Point pt = go.getSceneMinLocation();
                int p = go.getPlane();
                int x = pt.getX();
                int y = pt.getY();
                int template = client.getTopLevelWorldView().getInstanceTemplateChunks()[p][x / 8][y / 8];
                int roomtype = getroom_type(template);
                if (roomtype < 16) {
                    // add obstacle to list
                    cryp[roomtype] = p;
                    cryx[roomtype] = x + client.getTopLevelWorldView().getBaseX();
                    cryy[roomtype] = y + client.getTopLevelWorldView().getBaseY();
                }
                break;
        }
    }

    @Subscribe
    public void onGameObjectDespawned(GameObjectDespawned e) {
        if (e.getGameObject().getId() == ObjectID.OLM_HEAD) {
            send_split();
            olm_phase = ~olm_phase;
        }
    }

    private static final int SMOKE_PUFF = ObjectID.GNOME_GLIDERCRASHED;

    @Subscribe
    public void onGraphicsObjectCreated(GraphicsObjectCreated e) {
        if (config.splitIcePop() && e.getGraphicsObject().getId() == SMOKE_PUFF && !iceout && !config.regular()) {
            WorldPoint wp = WorldPoint.fromLocal(client, e.getGraphicsObject().getLocation());
            int p = client.getTopLevelWorldView().getPlane();
            int x = wp.getX() - client.getTopLevelWorldView().getBaseX();
            int y = wp.getY() - client.getTopLevelWorldView().getBaseY();
            int template = client.getTopLevelWorldView().getInstanceTemplateChunks()[p][x / 8][y / 8];
            if (CoxUtil.getroom_type(template) == ICE_DEMON) {
                send_split();
                iceout = true;
            }
        }
    }

    @Subscribe
    public void onVarbitChanged(VarbitChanged e)
    {
        // when the raid starts
        int raidState = client.getVarbitValue(VarbitID.RAIDS_CLIENT_PROGRESS);
        if (prevRaidState == 0 && raidState == 1){
            if (config.autoReset()) {
                send_reset();
            }
            send_split();
        }
        prevRaidState = raidState;
    }

    private void send_split() {
        try {
            writer.write("startorsplit\r\n");
            writer.flush();
        } catch (Exception ignored) { }
    }

    private void send_reset() {
        try {
            writer.write("reset\r\n");
            writer.flush();
        } catch (Exception ignored) { }
    }

    @Override
    protected void startUp() {
        final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/icon.png");
        panel = new CoxCMAutoSplitterPanel(client, writer, config, this);
        navButton = NavigationButton.builder().tooltip("LiveSplit controller")
                .icon(icon).priority(6).panel(panel).build();
        clientToolbar.addNavigation(navButton);

        panel.startPanel();
    }

    @Override
    protected void shutDown() {
        clientToolbar.removeNavigation(navButton);
        panel.disconnect();  // terminates active socket
    }
}
