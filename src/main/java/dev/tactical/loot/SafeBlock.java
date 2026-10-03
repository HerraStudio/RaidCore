package dev.tactical.loot;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import dev.tactical.Tactical;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class SafeBlock extends BaseEntityBlock {
    public static final MapCodec<SafeBlock> CODEC = simpleCodec(SafeBlock::new);
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final BooleanProperty OPEN = BooleanProperty.create("open");
    private static final VoxelShape NORTH_SOUTH = Block.box(-2.1018, 0.32, -7.36, 18.1018, 22.2951, 23.36);
    private static final VoxelShape EAST_WEST = Block.box(-7.36, 0.32, -2.1018, 23.36, 22.2951, 18.1018);
    private static final VoxelShape CLOSED_NORTH = Block.box(-2.1018, .32, 3.7164, 18.1018, 22.2951, 23.36);
    private static final VoxelShape CLOSED_SOUTH = Block.box(-2.1018, .32, -7.36, 18.1018, 22.2951, 12.2836);
    private static final VoxelShape CLOSED_EAST = Block.box(-7.36, .32, -2.1018, 12.2836, 22.2951, 18.1018);
    private static final VoxelShape CLOSED_WEST = Block.box(3.7164, .32, -2.1018, 23.36, 22.2951, 18.1018);

    public SafeBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(OPEN, false));
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, OPEN);
    }

    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos position, CollisionContext context) {
        if(state.getValue(OPEN)) return state.getValue(FACING).getAxis() == Direction.Axis.X ? EAST_WEST : NORTH_SOUTH;
        return switch(state.getValue(FACING)) {
            case SOUTH -> CLOSED_SOUTH;
            case EAST -> CLOSED_EAST;
            case WEST -> CLOSED_WEST;
            default -> CLOSED_NORTH;
        };
    }

    @Override
    public MapCodec<SafeBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
        return new SafeBlockEntity(position, state);
    }

    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type, Tactical.SAFE_ENTITY.get(), SafeBlockEntity::tick);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos position,
            Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(position) instanceof SafeBlockEntity safe
                && safe.stillValid(serverPlayer)) {
            if(state.getValue(OPEN)) safe.requestOpen(serverPlayer);
            else serverPlayer.displayClientMessage(net.minecraft.network.chat.Component.literal("按 F 破译保险箱"),true);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override protected java.util.List<net.minecraft.world.item.ItemStack> getDrops(BlockState state,
            net.minecraft.world.level.storage.loot.LootParams.Builder params) {
        var drops=super.getDrops(state,params);
        var entity=params.getOptionalParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_ENTITY);
        if(state.getValue(OPEN) || entity instanceof SafeBlockEntity safe && safe.rewardGenerated()) {
            for(var stack:drops) if(stack.is(Tactical.SAFE_ITEM.get())) {
                var tag=new net.minecraft.nbt.CompoundTag(); tag.putBoolean("HackRewardGenerated",true);
                stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(tag));
            }
        }
        return drops;
    }

    @Override public void setPlacedBy(Level level,BlockPos position,BlockState state,
            net.minecraft.world.entity.LivingEntity placer,net.minecraft.world.item.ItemStack stack) {
        super.setPlacedBy(level,position,state,placer,stack);
        var data=stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if(!level.isClientSide && data!=null && data.copyTag().getBoolean("HackRewardGenerated")
                && level.getBlockEntity(position) instanceof SafeBlockEntity safe) {
            safe.markRewardGenerated();
            level.setBlock(position,state.setValue(OPEN,true),3);
        }
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos position) {
        return AbstractContainerMenu.getRedstoneSignalFromBlockEntity(level.getBlockEntity(position));
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos position, BlockState replacement,
            boolean moving) {
        if (!level.isClientSide && !state.is(replacement.getBlock())
                && level.getBlockEntity(position) instanceof SafeBlockEntity safe) {
            Containers.dropContents(level, position, safe);
            safe.dropPendingRewards();
            safe.clearContent();
            level.updateNeighbourForOutputSignal(position, this);
        }
        super.onRemove(state, level, position, replacement, moving);
    }
}
