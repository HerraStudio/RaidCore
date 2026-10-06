package dev.herrastudio.raidcore.integration;

import dev.herrastudio.raidcore.RaidCore;
import dev.tactical.BagMenu;
import dev.tactical.client.BagScreen;
import dev.tactical.loot.*;
import dev.tactical.loot.client.LootAdminScreen;
import dev.tactical.loot.client.LootClient;
import dev.tactical.profile.*;
import dev.tactical.profile.client.ProfileClient;
import dev.tactical.profile.client.ProfileScreen;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.slf4j.LoggerFactory;

import static dev.herrastudio.raidcore.integration.RaidCoreSmokeChecks.require;

/** Real packets, the existing item editor, F input and a persistent ordinary-block inventory. */
@EventBusSubscriber(modid=RaidCore.MOD_ID,value=Dist.CLIENT)
public final class RaidCoreLootSmoke {
    private static final BlockPos CHEST=new BlockPos(0,79,0),BARREL=new BlockPos(3,79,0),VIRTUAL=new BlockPos(6,79,0);
    private static final String VIRTUAL_ID=net.neoforged.fml.ModList.get().isLoaded("doomsday_decoration")?"doomsday_decoration:acrate_2":"minecraft:stone";
    private static boolean started;
    private static int ticks,phase,wait,gunId;
    private static String capture;
    private static Runnable afterCapture;
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) throws Exception {
        if(!Boolean.getBoolean("raidcore.lootSmoke")) return;
        var mc=Minecraft.getInstance();
        if(!started && mc.screen instanceof TitleScreen && mc.getOverlay()==null) {
            require(mc.gameDirectory.toPath().toRealPath().equals(Path.of(System.getProperty("raidcore.smoke.directory")).toRealPath()),"Loot fixture is not isolated");
            started=true;mc.getWindow().setWindowed(1440,900);mc.options.guiScale().set(2);mc.options.renderDistance().set(3);
            mc.options.pauseOnLostFocus=false;mc.resizeDisplay();
            var settings=new LevelSettings("Loot management regression",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,
                    new GameRules(),WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel("loot-management-"+System.currentTimeMillis(),settings,new WorldOptions(43,false,false),
                    registry->registry.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),mc.screen);
        }
        if(!started || mc.player==null || ticks==0 && mc.screen!=null) return;
        ticks++;wait++;require(ticks<1100,"Loot harness stalled at "+phase);
        if(phase==0 && wait>=20) {
            server(player->{
                player.serverLevel().setDayTime(6000);
                player.serverLevel().getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,player.server);
                for(int x=-4;x<=11;x++) for(int z=-5;z<=5;z++) player.serverLevel().setBlockAndUpdate(new BlockPos(x,78,z),Blocks.SMOOTH_STONE.defaultBlockState());
                player.serverLevel().setBlockAndUpdate(CHEST,Blocks.CHEST.defaultBlockState());
                player.serverLevel().setBlockAndUpdate(BARREL,Blocks.BARREL.defaultBlockState());
                var virtualBlock=net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse(VIRTUAL_ID));
                require(!virtualBlock.defaultBlockState().isAir(),"Configured mod block missing from the registry");
                player.serverLevel().setBlockAndUpdate(VIRTUAL,virtualBlock.defaultBlockState());
                require(player.serverLevel().getBlockEntity(VIRTUAL)==null,"Fixture must be a decoration block without native storage");
                ((net.minecraft.world.Container)player.serverLevel().getBlockEntity(CHEST)).setItem(0,new ItemStack(Items.BREAD,3));
                var config=LootConfigData.get(player.server);
                LootService.edit(player,new LootPackets.Edit(800,config.snapshot().revision(),VIRTUAL_ID,"",1,true));
                player.teleportTo(player.serverLevel(),6.5,79,-2,0,24);
                require(LootService.open(player,VIRTUAL).isPresent(),"An ordinary block with an unconfigured pool cannot open");
                require(!BlockLootData.get(player.server).get(player.serverLevel(),VIRTUAL).generated(),"An empty unconfigured pool consumed the only loot roll");
                player.closeContainer();
                player.teleportTo(player.serverLevel(),.5,79,-2,0,24);
                player.getInventory().setItem(4,new ItemStack(Items.BREAD,5));player.getInventory().selected=4;
                return true;
            });
            ProfileClient.requestOpen();next(1);
        } else if(phase==1 && wait>=12) {
            require(mc.screen instanceof ProfileScreen,"Existing item configuration did not open");
            var screen=(ProfileScreen)mc.screen;screen.select(new ItemProfile.Key("minecraft:diamond",""));
            screen.setRarity(Rarity.RARE);screen.widthField.setText("1");screen.heightField.setText("1");
            screen.chanceField.setText("25");screen.setLootable(true);screen.saveDraft(false);next(2);
        } else if(phase==2 && wait>=14) {
            var profile=ItemProfiles.client().entries().get(new ItemProfile.Key("minecraft:diamond",""));
            require(profile!=null && profile.lootable() && profile.baseDropChance()==.25,"Actual profile packet lost the loot probability");
            capture="loot-item-editor.png";afterCapture=LootClient::requestOpen;next(3);
        } else if(phase==3 && wait>=12) {
            require(mc.screen instanceof LootAdminScreen,"Unified container page did not open");
            var screen=(LootAdminScreen)mc.screen;screen.search.setText(VIRTUAL_ID);screen.select(VIRTUAL_ID);screen.nameField.setText("工业物资箱");
            screen.multiplierField.setText("4");screen.setEnabled(true);screen.saveDraft();next(4);
        } else if(phase==4 && wait>=14) {
            var rule=LootSettings.client().rule(VIRTUAL_ID);
            require(rule!=null && rule.enabled() && rule.name().equals("工业物资箱") && rule.multiplier()==4,"Container save did not synchronize");
            capture="loot-container-editor.png";
            afterCapture=()->{
                var screen=(LootAdminScreen)mc.screen;screen.select("minecraft:chest");screen.nameField.setText("装备箱");
                screen.multiplierField.setText("4");screen.setEnabled(true);screen.saveDraft();
            };next(5);
        } else if(phase==5 && wait>=14) {
            persistenceAndPermissions();mc.screen.onClose();mc.gui.getChat().clearMessages(true);mc.getToasts().clear();
            mc.player.getInventory().selected=4;mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(4));next(6);
        } else if(phase==6 && wait>=12) {
            require(CHEST.equals(LootClient.target()),"Crosshair does not select the chest: "+LootClient.target());
            require(LootClient.name(CHEST).getString().equals("装备箱"),"HUD name does not use the container configuration");
            capture="loot-f-prompt.png";
            mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(CHEST),Direction.NORTH,CHEST,false));
            next(7);
        } else if(phase==7 && wait>=12) {
            require(mc.screen==null && server(player->player.containerMenu==player.inventoryMenu),"Right click still opens loot");
            KeyMapping.click(LootClient.SEARCH.getKey());next(8);
        } else if(phase==8 && wait>=65) {
            require(mc.screen instanceof BagScreen,"F did not open the existing loot interface");
            server(player->{
                require(player.containerMenu instanceof BagMenu menu && menu.loot()!=null,"Server did not use the existing search session");
                var container=(net.minecraft.world.Container)player.serverLevel().getBlockEntity(CHEST);
                require(count(container,Items.DIAMOND)==1 && count(container,Items.BREAD)==3,"Generated loot replaced existing contents or ignored the coefficient");
                require(player.getMainHandItem().is(Items.BREAD) && player.getOffhandItem().isEmpty(),"F also swapped offhand");
                return true;
            });
            capture="loot-search-ui.png";afterCapture=()->{
                server(player->{
                    var container=(net.minecraft.world.Container)player.serverLevel().getBlockEntity(CHEST);
                    for(int slot=0;slot<container.getContainerSize();slot++) if(container.getItem(slot).is(Items.DIAMOND)) container.removeItem(slot,1);
                    return true;
                });mc.player.closeContainer();mc.setScreen(null);
            };next(9);
        } else if(phase==9 && wait>=12) {
            KeyMapping.click(LootClient.SEARCH.getKey());next(10);
        } else if(phase==10 && wait>=12) {
            require(server(player->count((net.minecraft.world.Container)player.serverLevel().getBlockEntity(CHEST),Items.DIAMOND)==0),"Reopening rerolled physical loot");
            mc.player.closeContainer();mc.setScreen(null);
            server(player->{player.teleportTo(player.serverLevel(),6.5,79,-2,0,24);return true;});next(11);
        } else if(phase==11 && wait>=16) {
            require(VIRTUAL.equals(LootClient.target()),"Ordinary configured block is not searchable");
            require(LootClient.name(VIRTUAL).getString().equals("工业物资箱"),"Ordinary block did not use the custom name");
            capture="loot-mod-block-prompt.png";
            KeyMapping.click(LootClient.SEARCH.getKey());next(12);
        } else if(phase==12 && wait>=60) {
            require(mc.screen instanceof BagScreen,"Ordinary block did not open the original search UI");
            server(player->{
                var store=BlockLootData.get(player.server);var inventory=store.get(player.serverLevel(),VIRTUAL);
                require(inventory.generated() && count(inventory,Items.DIAMOND)==1,"Ordinary block lost its generated inventory");
                require(inventory==store.get(player.serverLevel(),VIRTUAL),"Two viewers get different inventories");
                var restored=BlockLootData.load(store.save(new CompoundTag(),player.registryAccess()),player.registryAccess());
                var saved=restored.get(player.serverLevel(),VIRTUAL);
                require(saved.generated() && count(saved,Items.DIAMOND)==1,"Virtual inventory does not survive world save/load");
                return true;
            });
            capture="loot-ordinary-block-ui.png";afterCapture=()->{
                server(player->{BlockLootData.get(player.server).get(player.serverLevel(),VIRTUAL).clearContent();return true;});
                mc.player.closeContainer();mc.setScreen(null);
            };next(13);
        } else if(phase==13 && wait>=12) {
            KeyMapping.click(LootClient.SEARCH.getKey());next(14);
        } else if(phase==14 && wait>=12) {
            require(server(player->BlockLootData.get(player.server).get(player.serverLevel(),VIRTUAL).isEmpty()),"Ordinary block refilled after looting");
            mc.player.closeContainer();mc.setScreen(null);
            server(player->{
                var config=LootConfigData.get(player.server);
                LootService.edit(player,new LootPackets.Edit(803,config.snapshot().revision(),"minecraft:barrel","空物资桶",0,true));
                player.teleportTo(player.serverLevel(),3.5,79,-2,0,24);return true;
            });next(15);
        } else if(phase==15 && wait>=16) {
            KeyMapping.click(LootClient.SEARCH.getKey());next(16);
        } else if(phase==16 && wait>=12) {
            require(mc.screen instanceof BagScreen && server(player->((net.minecraft.world.Container)player.serverLevel().getBlockEntity(BARREL)).isEmpty()),"Zero multiplier still generated loot");
            mc.player.closeContainer();mc.setScreen(null);
            server(player->{
                var left=new BlockPos(9,79,3);var right=left.east();
                var chest=Blocks.CHEST.defaultBlockState().setValue(net.minecraft.world.level.block.ChestBlock.FACING,Direction.NORTH);
                player.serverLevel().setBlockAndUpdate(left,chest.setValue(net.minecraft.world.level.block.ChestBlock.TYPE,net.minecraft.world.level.block.state.properties.ChestType.LEFT));
                player.serverLevel().setBlockAndUpdate(right,chest.setValue(net.minecraft.world.level.block.ChestBlock.TYPE,net.minecraft.world.level.block.state.properties.ChestType.RIGHT));
                player.teleportTo(player.serverLevel(),9.5,79,1,0,24);
                require(LootService.open(player,left).isPresent(),"Double chest cannot open through F search service");
                require(count((net.minecraft.world.Container)player.serverLevel().getBlockEntity(left),Items.DIAMOND)
                        +count((net.minecraft.world.Container)player.serverLevel().getBlockEntity(right),Items.DIAMOND)==1,"Double chest rolled the pool twice");
                player.closeContainer();player.teleportTo(player.serverLevel(),3.5,79,-2,0,24);
                require(LootService.open(player,VIRTUAL).isEmpty(),"Forged request opened a container outside the crosshair");
                var config=LootConfigData.get(player.server);
                LootService.edit(player,new LootPackets.Edit(804,config.snapshot().revision(),"minecraft:barrel","",1,false));return true;
            });next(17);
        } else if(phase==17 && wait>=14) {
            require(LootClient.target()==null,"Disabled container still has an F prompt");
            server(player->{
                player.teleportTo(player.serverLevel(),.5,79,-2,0,29);
                var gun=net.minecraft.core.registries.BuiltInRegistries.ITEM.stream().filter(item->item instanceof com.sgr792.gwo.item.GunItem<?>
                        && !(item instanceof com.sgr792.gwo.item.ContentMeleeItem)).findFirst().orElseThrow().getDefaultInstance();
                var entity=new net.minecraft.world.entity.item.ItemEntity(player.serverLevel(),.5,79.95,-1,gun);
                entity.setNoGravity(true);entity.setDeltaMovement(Vec3.ZERO);entity.setPickUpDelay(0);
                player.serverLevel().addFreshEntity(entity);gunId=entity.getId();return true;
            });next(18);
        } else if(phase==18 && wait>=18) {
            require(CHEST.equals(LootTargeting.find(mc.player)),"Gun fixture does not overlap the chest ray");
            require(LootClient.target()==null,"Loot prompt competes with an aimed ground gun");
            capture="loot-gun-priority.png";KeyMapping.click(LootClient.SEARCH.getKey());next(19);
        } else if(phase==19 && wait>=18) {
            require(mc.screen==null && server(player->player.serverLevel().getEntity(gunId)==null),"F did not preserve the existing gun pickup action");
            LoggerFactory.getLogger("RaidCoreLootSmoke").info("RAIDCORE_LOOT_MANAGEMENT_PASS: real editors and packets, base chance times coefficient, names, F opens existing search UI, right-click blocked, offhand preserved, ordinary blocks, save/load, shared inventory, no reroll, zero coefficient, permission and revision gates");
            LoggerFactory.getLogger("RaidCoreLootSmoke").info("RAIDCORE_LOOT_PICKUP_PRIORITY_PASS: real ground gun and chest on the same ray, only pickup prompt/action owns F");
            LoggerFactory.getLogger("RaidCoreLootSmoke").info("RAIDCORE_MODDED_BLOCK_LOOT_PASS: "+VIRTUAL_ID+", no native inventory, real editor/search, custom name/coefficient, F prompt, original search menu, shared saved inventory and no refill");
            mc.stop();next(20);
        }
    }
    private static void persistenceAndPermissions() {
        server(player->{
            var config=LootConfigData.get(player.server);
            require(LootConfigData.load(config.save(new CompoundTag(),player.registryAccess()),player.registryAccess()).snapshot().equals(config.snapshot()),"Container config persistence failed");
            var profiles=ProfileSavedData.get(player.server);
            require(ProfileSavedData.load(profiles.save(new CompoundTag(),player.registryAccess()),player.registryAccess()).snapshot().equals(profiles.snapshot()),"Item probability persistence failed");
            var legacy=profiles.save(new CompoundTag(),player.registryAccess());
            var row=legacy.getList("profiles",net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0);row.remove("lootable");row.remove("baseDropChance");
            var old=ProfileSavedData.load(legacy,player.registryAccess()).snapshot().entries().values().iterator().next();
            require(!old.lootable() && old.baseDropChance()==0 && old.rarity()==Rarity.RARE,"Legacy item rule migration lost rarity or generated loot");
            var visitor=new ServerPlayer(player.server,player.serverLevel(),new com.mojang.authlib.GameProfile(UUID.randomUUID(),"LootVisitor"),net.minecraft.server.level.ClientInformation.createDefault());
            visitor.connection=new net.minecraft.server.network.ServerGamePacketListenerImpl(player.server,new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND),visitor,
                    net.minecraft.server.network.CommonListenerCookie.createInitial(visitor.getGameProfile(),false)) {
                @Override public void send(net.minecraft.network.protocol.Packet<?> packet) { }
            };
            require(!visitor.hasPermissions(2),"Visitor unexpectedly has admin permissions");
            int revision=config.snapshot().revision();
            LootService.edit(visitor,new LootPackets.Edit(801,revision,VIRTUAL_ID,"forged",100,true));
            require(config.snapshot().revision()==revision,"Non-admin modified loot rules");
            LootService.edit(player,new LootPackets.Edit(802,revision-1,VIRTUAL_ID,"stale",100,true));
            require(config.snapshot().revision()==revision,"Stale editor overwrote new configuration");
            return true;
        });
    }
    private static int count(net.minecraft.world.Container container,net.minecraft.world.item.Item item) {
        int count=0;for(int slot=0;slot<container.getContainerSize();slot++) if(container.getItem(slot).is(item)) count+=container.getItem(slot).getCount();return count;
    }
    @SubscribeEvent public static void frame(RenderFrameEvent.Post event) throws Exception {
        if(capture==null) return;
        var mc=Minecraft.getInstance();try(var png=Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            png.writeToFile(mc.gameDirectory.toPath().resolve(capture));
        }
        capture=null;
        var action=afterCapture;afterCapture=null;if(action!=null) action.run();
    }
    private static <T> T server(Function<ServerPlayer,T> work) {
        var mc=Minecraft.getInstance();var id=mc.player.getUUID();
        return mc.getSingleplayerServer().submit(()->work.apply(mc.getSingleplayerServer().getPlayerList().getPlayer(id))).join();
    }
    private static void next(int value) {phase=value;wait=0;}
    private RaidCoreLootSmoke() {}
}
