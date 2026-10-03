package dev.tactical.profile;

import com.google.gson.JsonParser;
import com.sgr792.gwo.GwoMod;
import com.sgr792.gwo.content.*;
import com.sgr792.gwo.item.GunData;
import dev.tactical.*;
import dev.tactical.client.BagLayout;
import dev.tactical.client.BagScreen;
import dev.tactical.loot.LootSession;
import dev.tactical.profile.client.ProfileScreen;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid=dev.herrastudio.raidcore.RaidCore.MOD_ID,value=Dist.CLIENT)
public final class ProfileSmoke {
    private static boolean started,isolated;
    private static int ticks,stage,wait,index;
    private static String image;
    private static final Item[] ITEMS={Items.STONE,Items.IRON_INGOT,Items.LAPIS_LAZULI,Items.AMETHYST_SHARD,Items.GOLD_INGOT};
    private static ItemStack gunA,gunB;
    private static LootSession chest;
    @SubscribeEvent public static void isolate(RenderFrameEvent.Pre event) {
        long window=Minecraft.getInstance().getWindow().getWindow();
        if(!isolated) {
            GLFW.glfwSetMouseButtonCallback(window,null); GLFW.glfwSetKeyCallback(window,null);
            GLFW.glfwSetCursorPosCallback(window,null); GLFW.glfwSetScrollCallback(window,null); isolated=true;
        }
        if(GLFW.glfwGetWindowAttrib(window,GLFW.GLFW_VISIBLE)!=0) GLFW.glfwHideWindow(window);
    }
    @SubscribeEvent public static void frame(RenderFrameEvent.Post event) throws Exception {
        if(image==null) return;
        try(var png=Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) { png.writeToFile(Path.of(image)); }
        image=null;
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) throws Exception {
        var mc=Minecraft.getInstance();
        if(!started && mc.screen instanceof TitleScreen && mc.getOverlay()==null) {
            started=true; mc.getWindow().setWindowed(1440,900); mc.options.guiScale().set(2);
            mc.options.renderDistance().set(3); mc.options.pauseOnLostFocus=false; mc.resizeDisplay();
            var settings=new LevelSettings("Profiles integration",GameType.SURVIVAL,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel("profile-smoke-"+System.currentTimeMillis(),settings,new WorldOptions(18,false,false),
                    registry->registry.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),mc.screen);
        }
        if(!started || mc.player==null || ticks==0 && mc.screen!=null) return;
        ticks++; wait++;
        if(ticks>1100) throw new IllegalStateException("Profile smoke timeout stage "+stage);
        if(ticks==20) {
            var definitions=new LinkedHashMap<ResourceLocation,WeaponDefinition>(WeaponContentRegistry.weaponDefinitions());
            for(String name:new String[]{"carbine_a","carbine_b"}) {
                var json=JsonParser.parseString("""
                        {"display_name":"Profile QA rifle","weapon_type":"firearm","magazine_size":30,"damage":8,"range":96,
                        "fire_modes":["semi"],"mechanics":{"rpm":600,"fire_interval_ms":100},
                        "ballistics":{"muzzle_velocity":100,"gravity":0,"life_seconds":2.5},"first_person_arms":false}
                        """).getAsJsonObject();
                definitions.put(ResourceLocation.fromNamespaceAndPath("profile_smoke",name),new FirearmDefinition(GunDefinition.fromJson(json)));
            }
            WeaponContentRegistry.installBootstrapDefinitions(definitions);
            gunA=GwoMod.weaponStack(ResourceLocation.parse("profile_smoke:carbine_a"));
            gunB=GwoMod.weaponStack(ResourceLocation.parse("profile_smoke:carbine_b"));
            GunData.initialize(gunA,ResourceLocation.parse("profile_smoke:carbine_a"),WeaponContentRegistry.get(ResourceLocation.parse("profile_smoke:carbine_a")));
            GunData.initialize(gunB,ResourceLocation.parse("profile_smoke:carbine_b"),WeaponContentRegistry.get(ResourceLocation.parse("profile_smoke:carbine_b")));
            GunData.setAmmo(gunA,7);
            value(p->{
                require(p.hasPermissions(2),"Fixture needs administrator rights");
                var state=BagState.of(p); state.entries.clear(); p.getInventory().clearContent();
                state.gear[1]=new ItemStack(Tactical.RIG.get()); state.gear[2]=new ItemStack(Tactical.BACKPACK.get());
                p.getInventory().setItem(0,gunA.copy()); p.getInventory().setItem(1,gunB.copy());
                for(int i=0;i<5;i++) p.getInventory().setItem(i+4,new ItemStack(ITEMS[i],i+2));
                var stone=new ItemStack(Items.STONE,17); stone.set(DataComponents.CUSTOM_NAME,Component.literal("完整保留 QA"));
                state.entries.add(new BagState.Entry(state.nextId++,1,0,0,false,stone));
                state.entries.add(new BagState.Entry(state.nextId++,1,1,0,false,new ItemStack(Items.DIAMOND,5)));
                state.entries.add(new BagState.Entry(state.nextId++,1,5,5,false,new ItemStack(Items.GOLD_INGOT,4)));
                state.entries.add(new BagState.Entry(state.nextId++,1,2,2,false,new ItemStack(Items.DIAMOND_SWORD)));
                state.save(p);
                var box=new SimpleContainer(27); box.setItem(0,new ItemStack(Items.STONE,11)); box.setItem(1,new ItemStack(Items.IRON_INGOT,8));
                chest=new LootSession(ChestMenu.threeRows(0,p.getInventory(),box),Component.literal("测试箱"));
                return true;
            });
            mc.getConnection().sendCommand("tacticalitems"); stage=1; wait=0;
        }
        if(stage==1 && wait>=15) {
            require(mc.screen instanceof ProfileScreen,"Server command did not open LDLib2 editor");
            require(((ProfileScreen)mc.screen).modularUI.ui.getRootElement()!=null,"Missing LDLib tree");
            configure((ProfileScreen)mc.screen,ItemProfiles.key(new ItemStack(ITEMS[index])),1,1,Rarity.values()[index]);
            stage=2; wait=0;
        }
        if(stage==2 && wait>=12) {
            require(ItemProfiles.rarity(new ItemStack(ITEMS[index]))==Rarity.values()[index],"Rarity save was not synchronized");
            require(value(p->ItemProfiles.rarity(new ItemStack(ITEMS[index]))==Rarity.values()[index]),"Server/client rarity differs");
            index++;
            if(index<5) {stage=1;wait=0;}
            else { configure((ProfileScreen)mc.screen,ItemProfiles.key(gunA),5,2,Rarity.EPIC); stage=3;wait=0; }
        }
        if(stage==3 && wait>=12) {
            require(ItemProfiles.rarity(gunA)==Rarity.EPIC && ItemProfiles.rarity(gunB)==Rarity.COMMON,"GWO models share rarity incorrectly");
            require(GunData.ammo(gunA)==7,"Preview or config mutated gun ammo");
            configure((ProfileScreen)mc.screen,ItemProfiles.key(new ItemStack(Items.DIAMOND_SWORD)),5,2,Rarity.LEGENDARY);
            stage=4;wait=0;
        }
        if(stage==4 && wait>=16) {
            image="profile-editor-ui.png";
            var screen=(ProfileScreen)mc.screen;
            require(screen.widthField.getText().equals("5") && screen.heightField.getText().equals("2"),"Dimension editor did not retain saved fields");
            require(Rules.size(new ItemStack(Items.DIAMOND_SWORD)).equals(new Rules.Size(5,2)),"Configured multi-cell size not used by client");
            value(p->{
                require(Rules.size(new ItemStack(Items.DIAMOND_SWORD)).equals(new Rules.Size(5,2)),"Configured size not used server-side");
                var store=ProfileSavedData.get(p.getServer());
                var restored=ProfileSavedData.load(store.save(new CompoundTag(),p.registryAccess()),p.registryAccess());
                require(restored.snapshot().equals(store.snapshot()),"Saved world configuration cannot round-trip");
                // Non-operator server player with a sink connection tests the actual mutation gate.
                var visitor=new net.minecraft.server.level.ServerPlayer(p.getServer(),p.serverLevel(),
                        new com.mojang.authlib.GameProfile(UUID.randomUUID(),"RarityVisitor"),net.minecraft.server.level.ClientInformation.createDefault());
                visitor.connection=new net.minecraft.server.network.ServerGamePacketListenerImpl(p.getServer(),
                        new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND),visitor,
                        net.minecraft.server.network.CommonListenerCookie.createInitial(visitor.getGameProfile(),false)) {
                    @Override public void send(net.minecraft.network.protocol.Packet<?> packet) { }
                };
                require(!visitor.hasPermissions(2),"Non-admin fixture unexpectedly authorized");
                int revision=store.snapshot().revision();
                ProfileService.edit(visitor,new ProfilePackets.Edit(1,revision,"minecraft:diamond","",6,6,"LEGENDARY",false));
                require(store.snapshot().revision()==revision,"Non-admin changed server config");
                return true;
            });
            log("PROFILE_SYNC_PERMISSION_PASS: real GUI clicks save all five tiers, GWO variants isolated, ammo intact, persistent NBT and non-admin rejected");
            stage=5;wait=0;
        }
        if(stage==5 && wait>=12) {
            mc.screen.onClose(); PacketDistributor.sendToServer(new Packets.Action(0,0,0,0,0,0,0,false,false));
            stage=6;wait=0;
        }
        if(stage==6 && wait>=15) {
            require(mc.screen instanceof BagScreen,"Bag not opened"); image="profile-inventory-ui.png";
            var fit=BagLayout.fit(mc.screen.width,mc.screen.height);
            mc.screen.mouseClicked(fit.screenX(530),fit.screenY(25),0);
            stage=7;wait=0;
        }
        if(stage==7 && wait>=12) {
            require(mc.screen instanceof ProfileScreen,"Inventory button did not open profiles");
            configure((ProfileScreen)mc.screen,ItemProfiles.key(new ItemStack(Items.STONE)),6,6,Rarity.RARE);
            stage=8;wait=0;
        }
        if(stage==8 && wait>=16) {
            value(p->{
                var state=BagState.of(p);
                int named=state.entries.stream().filter(e->e.stack.is(Items.STONE) && e.stack.getHoverName().getString().equals("完整保留 QA")).mapToInt(e->e.stack.getCount()).sum();
                require(named==17,"Resizing lost stack count or components");
                require(p.getInventory().getItem(4).isEmpty(),"Oversized stone remained in 1x1 pocket");
                require(state.entries.stream().anyMatch(e->e.area==2),"No recovery protection for overcapacity");
                chest.refresh(); var rect=chest.layout().stream().filter(e->e.id()==LootSession.id(0)).findFirst().orElseThrow().rectangle();
                require(rect.w()==6 && rect.h()==6,"Existing container packing kept old footprint");
                return true;
            });
            int before=ItemProfiles.client().revision();
            PacketDistributor.sendToServer(new ProfilePackets.Edit(991,before,"minecraft:stone","",0,7,"LEGENDARY",false));
            PacketDistributor.sendToServer(new ProfilePackets.Edit(992,before-1,"minecraft:stone","",1,1,"COMMON",false));
            stage=9;wait=0;
        }
        if(stage==9 && wait>=12) {
            require(Rules.size(new ItemStack(Items.STONE)).equals(new Rules.Size(6,6)),"Malformed/stale edits changed profile");
            var screen=(ProfileScreen)mc.screen;
            screen.select(ItemProfiles.key(new ItemStack(Items.STONE))); click(screen,327,378); stage=10;wait=0;
        }
        if(stage==10 && wait>=12) {
            require(Rules.size(new ItemStack(Items.STONE)).equals(new Rules.Size(1,1)),"Reset did not restore defaults");
            value(p->{
                chest.refresh(); var roots=chest.layout().stream().map(LootSession.LayoutEntry::rectangle).toList();
                var store=ProfileSavedData.get(p.getServer());
                var key=ItemProfiles.key(new ItemStack(Items.IRON_INGOT)); var old=store.snapshot().entries().get(key);
                ProfileService.edit(p,new ProfilePackets.Edit(995,store.snapshot().revision(),key.item(),key.content(),old.width(),old.height(),"LEGENDARY",false));
                chest.refresh();
                require(roots.equals(chest.layout().stream().map(LootSession.LayoutEntry::rectangle).toList()),"Rarity-only edit changed container roots");
                return true;
            });
            screenSearch((ProfileScreen)mc.screen);
            log("PROFILE_RESIZE_RESET_PASS: whole footprints, pocket migration, recovery retains names/counts, live loot repacking, invalid/stale rejection and default reset");
            log("PROFILE_NATIVE_SMOKE_PASS: administrator GUI, five rarity colors, live preview, command/button, server sync and persistence");
            mc.stop();
        }
    }
    private static void screenSearch(ProfileScreen screen) {
        screen.search.setText("minecraft:diamond");
        require(screen.search.getText().equals("minecraft:diamond"),"LDLib search text field failed");
    }
    private static void configure(ProfileScreen screen,ItemProfile.Key key,int width,int height,Rarity rarity) {
        screen.select(key); require(key.equals(screen.selectedKey()),"Item missing from catalog "+key);
        var published=ItemProfiles.client();
        screen.widthField.setText(Integer.toString(width)); screen.heightField.setText(Integer.toString(height));
        click(screen,260+rarity.ordinal()*79+35,117); require(screen.draftRarity()==rarity,"Rarity tile click missed");
        require(published.equals(ItemProfiles.client()),"Editor draft leaked into live placement rules");
        click(screen,535,378);
    }
    private static void click(ProfileScreen screen,double x,double y) {
        double scale=Math.min(1.2,Math.min((screen.width-24d)/680,(screen.height-24d)/410));
        screen.mouseClicked((screen.width-680*scale)/2+x*scale,(screen.height-410*scale)/2+y*scale,0);
    }
    private static <T> T value(Function<net.minecraft.server.level.ServerPlayer,T> action) {
        var mc=Minecraft.getInstance(); var id=mc.player.getUUID();
        return mc.getSingleplayerServer().submit(()->action.apply(mc.getSingleplayerServer().getPlayerList().getPlayer(id))).join();
    }
    private static void require(boolean condition,String message) { if(!condition) throw new IllegalStateException(message); }
    private static void log(String message) { LoggerFactory.getLogger("ProfileSmoke").info(message); }
}
