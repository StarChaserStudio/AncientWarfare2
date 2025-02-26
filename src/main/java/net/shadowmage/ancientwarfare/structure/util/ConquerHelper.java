package net.shadowmage.ancientwarfare.structure.util;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.MobEffects;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.shadowmage.ancientwarfare.core.config.AWCoreStatics;
import net.shadowmage.ancientwarfare.core.network.NetworkHandler;
import net.shadowmage.ancientwarfare.core.util.TextUtils;
import net.shadowmage.ancientwarfare.core.util.WorldTools;
import net.shadowmage.ancientwarfare.npc.AncientWarfareNPC;
import net.shadowmage.ancientwarfare.npc.entity.faction.NpcFaction;
import net.shadowmage.ancientwarfare.structure.init.AWStructureBlocks;
import net.shadowmage.ancientwarfare.structure.network.PacketHighlightBlock;
import net.shadowmage.ancientwarfare.structure.template.build.StructureBB;
import net.shadowmage.ancientwarfare.structure.tile.SpawnerSettings;
import net.shadowmage.ancientwarfare.structure.tile.TileAdvancedSpawner;

import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class ConquerHelper {
	private static final Cache<StructureBB, Boolean> STRUCTURE_BB_CONQUERED = CacheBuilder.newBuilder().expireAfterWrite(10, TimeUnit.SECONDS).build();

	private ConquerHelper() {}

	private static boolean checkBBConquered(World world, StructureBB bb) {
		return checkBBConquered(world, bb, npc -> {}, pos -> {});
	}

	public static boolean checkBBConquered(EntityPlayer player, StructureBB bb) {
		return checkBBConquered(player.getEntityWorld(), bb, npc -> markNpcAndMessagePlayer(player, npc), pos -> markSpawnerAndMessagePlayer(player, pos));
	}

	private static void markSpawnerAndMessagePlayer(EntityPlayer player, BlockPos pos) {
		NetworkHandler.sendToPlayer((EntityPlayerMP) player, new PacketHighlightBlock(new BlockHighlightInfo(pos, player.getEntityWorld().getTotalWorldTime() + 6000)));
		player.sendStatusMessage(new TextComponentTranslation("gui.ancientwarfarestructure.structure_spawner_present"), true);
	}

	private static void markNpcAndMessagePlayer(EntityPlayer player, NpcFaction npc) {
		npc.addPotionEffect(new PotionEffect(MobEffects.GLOWING, 6000));
		player.sendStatusMessage(new TextComponentTranslation("gui.ancientwarfarestructure.structure_hostile_alive",
				TextUtils.getSimpleBlockPosString(npc.getPosition())), true);
	}

	private static boolean checkBBConquered(World world, StructureBB bb, Consumer<NpcFaction> onHostileNpcFound, Consumer<BlockPos> onHostileSpawnerFound) {
		AxisAlignedBB boundingBox = bb.getAABB();
		ArrayList<NpcFaction> remainingEnemies = new ArrayList<>();
		ArrayList<BlockPos> remainingEnemyBlocks = new ArrayList<>();
		int resistanceValue = 0; // A measure of how much resistance is left in unkilled enemy NPCs.
		for (NpcFaction factionNpc : world.getEntitiesWithinAABB(NpcFaction.class, boundingBox)) {
			if (!factionNpc.isPassive()) {
				onHostileNpcFound.accept(factionNpc);
				remainingEnemies.add(factionNpc);
				//System.out.println("Found NPC with type="+factionNpc.getNpcFullType());
				if(factionNpc.getNpcFullType().contains("leader")) {
					resistanceValue += AWCoreStatics.bossConquerResistance; // Boss enemies count as 5
				}
				else if(factionNpc.getNpcFullType().contains("elite")) {
					resistanceValue += AWCoreStatics.eliteConquerResistance; // Elite enemies count as 2
				}
				else {
					resistanceValue += AWCoreStatics.normalConquerResistance;
				}
			}
		}

		for (BlockPos blockPos : BlockPos.getAllInBox(bb.min, bb.max)) {
			if (!world.isBlockLoaded(blockPos)) {
				return false;
			}
			if (world.getBlockState(blockPos).getBlock() == AWStructureBlocks.ADVANCED_SPAWNER &&
					WorldTools.getTile(world, blockPos, TileAdvancedSpawner.class).map(te -> SpawnerSettings.spawnsHostileNpcs(te.getSettings())).orElse(false)) {
				onHostileSpawnerFound.accept(blockPos);
				remainingEnemyBlocks.add(blockPos);
				resistanceValue += AWCoreStatics.spawnerConquerResistance;
			}
		}

		// If there are 5 or more enemies alive, structure cannot be claimed. Elites count as 2, bosses count as 5
		if(resistanceValue >= AWCoreStatics.conquerThreshold) {
			return false;
		}
		else {
			// Despawn all remaining enemies
			for (NpcFaction factionNpc : remainingEnemies) {
				factionNpc.setDropItemsWhenDead(false);
				factionNpc.setDead();
			}
			// Turn spawners to air
			for (BlockPos blockPos : remainingEnemyBlocks) {
				world.setBlockToAir(blockPos);
			}
			return true;
		}
	}

	public static boolean checkBBNotConquered(World world, StructureBB bb) {
		try {
			return !STRUCTURE_BB_CONQUERED.get(bb, () -> checkBBConquered(world, bb));
		}
		catch (ExecutionException e) {
			AncientWarfareNPC.LOG.error("Error getting conquered structureBB info ", e);
			return false;
		}
	}
}
