package dev.itemexplorer.cable;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/** Physical cable socket only. Device roles, inventory access and jobs belong to their own services. */
public interface DataCableEndpoint {
    boolean acceptsDataCable(BlockState state, Direction face);

    static boolean accepts(BlockState state, Direction face) {
        return state.getBlock() instanceof DataCableEndpoint endpoint && endpoint.acceptsDataCable(state, face);
    }
}
