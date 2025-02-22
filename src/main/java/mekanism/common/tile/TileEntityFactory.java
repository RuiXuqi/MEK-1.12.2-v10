package mekanism.common.tile;

import io.netty.buffer.ByteBuf;
import mekanism.api.EnumColor;
import mekanism.api.IConfigCardAccess.ISpecialConfigData;
import mekanism.api.TileNetworkList;
import mekanism.api.gas.*;
import mekanism.api.infuse.InfuseObject;
import mekanism.api.infuse.InfuseRegistry;
import mekanism.api.transmitters.TransmissionType;
import mekanism.common.*;
import mekanism.common.base.IComparatorSupport;
import mekanism.common.base.IFactory.MachineFuelType;
import mekanism.common.base.IFactory.RecipeType;
import mekanism.common.base.ISideConfiguration;
import mekanism.common.base.ISustainedData;
import mekanism.common.base.ITierUpgradeable;
import mekanism.common.block.states.BlockStateMachine.MachineType;
import mekanism.common.capabilities.Capabilities;
import mekanism.common.integration.computer.IComputerIntegration;
import mekanism.common.item.ItemBlockMachine;
import mekanism.common.recipe.GasConversionHandler;
import mekanism.common.recipe.RecipeHandler;
import mekanism.common.recipe.inputs.*;
import mekanism.common.recipe.machines.*;
import mekanism.common.recipe.outputs.*;
import mekanism.common.tier.BaseTier;
import mekanism.common.tier.FactoryTier;
import mekanism.common.tile.component.TileComponentConfig;
import mekanism.common.tile.component.TileComponentEjector;
import mekanism.common.tile.prefab.TileEntityAdvancedElectricMachine;
import mekanism.common.tile.prefab.TileEntityMachine;
import mekanism.common.util.*;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.NonNullList;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fml.common.FMLCommonHandler;
import org.apache.commons.lang3.tuple.ImmutablePair;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

@SuppressWarnings("rawtypes")
public class TileEntityFactory extends TileEntityMachine
        implements IComputerIntegration, ISideConfiguration, IGasHandler, ISpecialConfigData, ITierUpgradeable,
        ISustainedData, IComparatorSupport {

    private static final String[] methods = new String[] { "getEnergy", "getProgress", "facing", "canOperate",
            "getMaxEnergy", "getEnergyNeeded" };
    private final MachineRecipe[] cachedRecipe;
    /**
     * This Factory's tier.
     */
    public FactoryTier tier;
    /**
     * An int[] used to track all current operations' progress.
     */
    public int[] progress;
    public int BASE_MAX_INFUSE = 1000;
    public int maxInfuse;
    /**
     * How many ticks it takes, by default, to run an operation.
     */
    public int BASE_TICKS_REQUIRED;
    /**
     * How many ticks it takes, with upgrades, to run an operation
     */
    public int ticksRequired = 200;
    /**
     * How much secondary energy each operation consumes per tick
     */
    private double secondaryEnergyPerTick = 0;
    private int secondaryEnergyThisTick;
    /**
     * How long it takes this factory to switch recipe types.
     */
    private static int RECIPE_TICKS_REQUIRED = 40;
    /**
     * How many recipe ticks have progressed.
     */
    private int recipeTicks;

    /**
     * The amount of infuse this machine has stored.
     */
    public final InfuseStorage infuseStored = new InfuseStorage();

    public final GasTank gasTank;

    public boolean sorting;

    public boolean upgraded;

    public double lastUsage;

    public TileComponentEjector ejectorComponent;
    public TileComponentConfig configComponent;
    /**
     * This machine's recipe type.
     */
    @Nonnull
    private RecipeType recipeType = RecipeType.SMELTING;

    public TileEntityFactory() {
        this(FactoryTier.BASIC, MachineType.BASIC_FACTORY);
        configComponent = new TileComponentConfig(this, TransmissionType.ITEM, TransmissionType.ENERGY,
                TransmissionType.GAS);

        configComponent.addOutput(TransmissionType.ITEM, new SideData("None", EnumColor.GREY, InventoryUtils.EMPTY));
        configComponent.addOutput(TransmissionType.ITEM, new SideData("Input", EnumColor.RED, new int[] { 5, 6, 7 }));
        configComponent.addOutput(TransmissionType.ITEM,
                new SideData("Output", EnumColor.INDIGO, new int[] { 8, 9, 10 }));
        configComponent.addOutput(TransmissionType.ITEM,
                new SideData("Energy", EnumColor.BRIGHT_GREEN, new int[] { 1 }));
        configComponent.addOutput(TransmissionType.ITEM, new SideData("Extra", EnumColor.ORANGE, new int[] { 4 }));
        configComponent.addOutput(TransmissionType.ITEM,
                new SideData("Input_Extra", EnumColor.PURPLE, new int[] { 4, 5, 6, 7 }));
        configComponent.setConfig(TransmissionType.ITEM, new byte[] { 4, 0, 0, 3, 1, 2 });

        configComponent.addOutput(TransmissionType.GAS, new SideData("None", EnumColor.GREY, InventoryUtils.EMPTY));
        configComponent.addOutput(TransmissionType.GAS, new SideData("Gas", EnumColor.RED, new int[] { 0 }));
        configComponent.fillConfig(TransmissionType.GAS, 1);
        configComponent.setCanEject(TransmissionType.GAS, false);

        configComponent.setInputConfig(TransmissionType.ENERGY);

        ejectorComponent = new TileComponentEjector(this);
        ejectorComponent.setOutputData(TransmissionType.ITEM, configComponent.getOutputs(TransmissionType.ITEM).get(2));

    }

    public TileEntityFactory(FactoryTier type, MachineType machine) {
        super("null", machine, 0);
        tier = type;
        inventory = NonNullList.withSize(5 + type.processes * 2, ItemStack.EMPTY);
        progress = new int[type.processes];
        isActive = false;
        cachedRecipe = new MachineRecipe[tier.processes];
        gasTank = new GasTank(TileEntityAdvancedElectricMachine.MAX_GAS * tier.processes);
        maxInfuse = BASE_MAX_INFUSE * tier.processes;
        if (tier != FactoryTier.CREATIVE) {
            BASE_TICKS_REQUIRED = 200;
        } else {
            BASE_TICKS_REQUIRED = 1;
        }
        setRecipeType(recipeType);
    }

    @Override
    public boolean upgrade(BaseTier upgradeTier) {
        if (tier == FactoryTier.ELITE || tier == FactoryTier.ULTIMATE) {
            if (upgradeTier.ordinal() != tier.ordinal() + 1) {
                return false;
            }
            world.setBlockToAir(getPos());
            world.setBlockState(getPos(), MekanismBlocks.MachineBlock3.getStateFromMeta(4 + tier.ordinal() + 1), 3);
        } else if (tier == FactoryTier.BASIC || tier == FactoryTier.ADVANCED) {
            if (upgradeTier.ordinal() != tier.ordinal() + 1) {
                return false;
            }
            world.setBlockToAir(getPos());
            world.setBlockState(getPos(), MekanismBlocks.MachineBlock.getStateFromMeta(5 + tier.ordinal() + 1), 3);
        } else
            return false;

        TileEntityFactory factory = Objects.requireNonNull((TileEntityFactory) world.getTileEntity(getPos()));

        // Basic
        factory.facing = facing;
        factory.clientFacing = clientFacing;
        factory.ticker = ticker;
        factory.redstone = redstone;
        factory.redstoneLastTick = redstoneLastTick;
        factory.doAutoSync = doAutoSync;

        // Electric
        factory.electricityStored = electricityStored;

        // Factory
        System.arraycopy(progress, 0, factory.progress, 0, tier.processes);

        factory.recipeTicks = recipeTicks;
        factory.isActive = isActive;
        factory.prevEnergy = prevEnergy;
        factory.gasTank.setGas(gasTank.getGas());
        factory.sorting = sorting;
        factory.setControlType(getControlType());
        factory.upgradeComponent.readFrom(upgradeComponent);
        factory.ejectorComponent.readFrom(ejectorComponent);
        factory.configComponent.readFrom(configComponent);
        factory.ejectorComponent.setOutputData(TransmissionType.ITEM,
                factory.configComponent.getOutputs(TransmissionType.ITEM).get(2));
        factory.setRecipeType(recipeType);
        factory.upgradeComponent.setSupported(Upgrade.GAS, recipeType.fuelEnergyUpgrades());
        factory.securityComponent.readFrom(securityComponent);
        factory.infuseStored.copyFrom(infuseStored);

        for (int i = 0; i < tier.processes + 5; i++) {
            factory.inventory.set(i, inventory.get(i));
        }

        for (int i = 0; i < tier.processes; i++) {
            int output = getOutputSlot(i);
            if (!inventory.get(output).isEmpty()) {
                int newOutput = 5 + factory.tier.processes + i;
                factory.inventory.set(newOutput, inventory.get(output));
            }
        }

        for (Upgrade upgrade : factory.upgradeComponent.getSupportedTypes()) {
            factory.recalculateUpgradables(upgrade);
        }

        factory.upgraded = true;
        factory.markDirty();
        Mekanism.packetHandler.sendUpdatePacket(factory);
        return true;
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        if (!world.isRemote) {
            if (ticker == 1) {
                world.notifyNeighborsOfStateChange(getPos(), getBlockType(), true);
            }
            ChargeUtils.discharge(1, this);

            handleSecondaryFuel();
            sortInventory();
            ItemStack machineSwapItem = inventory.get(2);
            if (!machineSwapItem.isEmpty() && machineSwapItem.getItem() instanceof ItemBlockMachine
                    && inventory.get(3).isEmpty()) {

                MachineType swapType = MachineType.get(machineSwapItem);

                if (swapType != null && !swapType.isFactory()) {

                    RecipeType toSet = RecipeType.getFromMachineType(swapType);

                    if (toSet != null && recipeType != toSet) {
                        if (recipeTicks < RECIPE_TICKS_REQUIRED) {
                            recipeTicks++;
                        } else {
                            recipeTicks = 0;
                            ItemStack returnStack = getMachineStack();

                            upgradeComponent.write(ItemDataUtils.getDataMap(returnStack));
                            upgradeComponent.setSupported(Upgrade.GAS, toSet.fuelEnergyUpgrades());
                            upgradeComponent.read(ItemDataUtils.getDataMapIfPresentNN(machineSwapItem));

                            inventory.set(2, ItemStack.EMPTY);
                            inventory.set(3, returnStack);

                            setRecipeType(toSet);
                            gasTank.setGas(null);
                            secondaryEnergyPerTick = getSecondaryEnergyPerTick(recipeType);
                            world.notifyNeighborsOfStateChange(getPos(), getBlockType(), true);
                            MekanismUtils.saveChunk(this);
                        }
                    } else {
                        recipeTicks = 0;
                    }
                }
            } else {
                recipeTicks = 0;
            }

            double prev = getEnergy();
            if (tier == FactoryTier.CREATIVE) {
                energyPerTick = 0;
                electricityStored = Integer.MAX_VALUE;
            }
            secondaryEnergyThisTick = recipeType.fuelEnergyUpgrades() ? StatUtils.inversePoisson(secondaryEnergyPerTick)
                    : (int) Math.ceil(secondaryEnergyPerTick);
            for (int process = 0; process < tier.processes; process++) {
                if (MekanismUtils.canFunction(this) && canOperate(getInputSlot(process), getOutputSlot(process))
                        && getEnergy() >= energyPerTick && gasTank.getStored() >= secondaryEnergyThisTick) {
                    if ((progress[process] + 1) < ticksRequired) {
                        progress[process]++;
                        gasTank.draw(secondaryEnergyThisTick, true);
                        if (tier != FactoryTier.CREATIVE) {
                            electricityStored -= energyPerTick;
                        }

                    } else if ((progress[process] + 1) >= ticksRequired) {
                        operate(getInputSlot(process), getOutputSlot(process));
                        progress[process] = 0;
                        gasTank.draw(secondaryEnergyThisTick, true);
                        if (tier != FactoryTier.CREATIVE) {
                            electricityStored -= energyPerTick;
                        }
                    }
                }

                if (!canOperate(getInputSlot(process), getOutputSlot(process))) {
                    if (!(recipeType.getFuelType() == MachineFuelType.ADVANCED
                            || recipeType.getFuelType() == MachineFuelType.FARM
                                    /* || recipeType.getFuelType() == MachineFuelType.CULTIVATE */ && recipeType
                                            .hasRecipe(inventory.get(getInputSlot(process))))) {
                        progress[process] = 0;
                    }
                }
            }

            boolean hasOperation = false;

            for (int i = 0; i < tier.processes; i++) {
                if (canOperate(getInputSlot(i), getOutputSlot(i))) {
                    hasOperation = true;
                    break;
                }
            }
            if (MekanismUtils.canFunction(this) && hasOperation && getEnergy() >= energyPerTick
                    && gasTank.getStored() >= secondaryEnergyThisTick) {
                setActive(true);
            } else if (prevEnergy >= getEnergy()) {
                setActive(false);
            }
            lastUsage = prev - getEnergy();
            prevEnergy = getEnergy();
        }
    }

    @Nonnull
    public RecipeType getRecipeType() {
        return recipeType;
    }

    public void setRecipeType(@Nonnull RecipeType type) {
        recipeType = Objects.requireNonNull(type);
        BASE_MAX_ENERGY = maxEnergy = tier.processes
                * Math.max(0.5D * recipeType.getEnergyStorage(), recipeType.getEnergyUsage());
        BASE_ENERGY_PER_TICK = energyPerTick = recipeType.getEnergyUsage();
        upgradeComponent.setSupported(Upgrade.GAS, recipeType.fuelEnergyUpgrades());
        secondaryEnergyPerTick = getSecondaryEnergyPerTick(recipeType);

        if (type.getFuelType() == MachineFuelType.CHANCE) {
            SideData data = configComponent.getOutputs(TransmissionType.ITEM).get(2);
            // Append the "extra" slot to the available slots
            data.availableSlots = Arrays.copyOf(data.availableSlots, data.availableSlots.length + 1);
            data.availableSlots[data.availableSlots.length - 1] = 4;
        }

        if (type.getFuelType() == MachineFuelType.FARM) {
            SideData data = configComponent.getOutputs(TransmissionType.ITEM).get(2);
            // Append the "extra" slot to the available slots
            data.availableSlots = Arrays.copyOf(data.availableSlots, data.availableSlots.length + 1);
            data.availableSlots[data.availableSlots.length - 1] = 4;
        }

        for (Upgrade upgrade : upgradeComponent.getSupportedTypes()) {
            recalculateUpgradables(upgrade);
        }
        if (hasWorld() && world.isRemote) {
            setSoundEvent(type.getSound());
        }
    }

    @Override
    public boolean sideIsConsumer(EnumFacing side) {
        return configComponent.hasSideForData(TransmissionType.ENERGY, facing, 1, side);
    }

    public void sortInventory() {
        if (sorting) {
            int[] inputSlots;
            if (tier == FactoryTier.BASIC) {
                inputSlots = new int[] { 5, 6, 7 };
            } else if (tier == FactoryTier.ADVANCED) {
                inputSlots = new int[] { 5, 6, 7, 8, 9 };
            } else if (tier == FactoryTier.ELITE) {
                inputSlots = new int[] { 5, 6, 7, 8, 9, 10, 11 };
            } else if (tier == FactoryTier.ULTIMATE) {
                inputSlots = new int[] { 5, 6, 7, 8, 9, 10, 11, 12, 13 };
            } else if (tier == FactoryTier.CREATIVE) {
                inputSlots = new int[] { 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15 };
            } else {
                // If something went wrong finding the tier don't sort it
                return;
            }
            Map<String, ProcessDescription> recipeSlots = new HashMap<>();
            List<Integer> freeSlots = new ArrayList<>();
            List<Integer> outputBlockedSlots = new ArrayList<>();
            for (int slotID : inputSlots) {
                ItemStack inStack = inventory.get(slotID);
                ItemStack outStack = inventory.get(slotID + tier.processes);
                MachineRecipe slotRecipe = getSlotRecipe(slotID, inStack);
                if (slotRecipe != null) {
                    ItemStack recipeIn = getRecipeInput(slotRecipe);
                    ItemStack recipeOut = getRecipeOutput(slotRecipe);

                    // We don't care about extras here since there's no possibility to have
                    // recipes with different extras in one factory at the same time
                    String id = new ImmutablePair<>(recipeIn, recipeOut).toString();

                    if (!recipeSlots.containsKey(id)) {
                        recipeSlots.put(id, new ProcessDescription(recipeIn, recipeOut));
                    }

                    recipeSlots.get(id).occupySlot(slotID);
                } else {
                    if (outStack.getCount() <= 0 || outStack.isEmpty()) {
                        freeSlots.add(slotID);
                    } else {
                        outputBlockedSlots.add(slotID);
                    }
                }
            }
            if (recipeSlots.size() == 0)
                return;

            int freePerSlot = freeSlots.size() / recipeSlots.size();
            int excess = freeSlots.size() - (freePerSlot * recipeSlots.size());

            for (Map.Entry<String, ProcessDescription> line : recipeSlots.entrySet()) {
                int takeSlots = freePerSlot;

                if (excess > 0) {
                    takeSlots += 1;
                    excess -= 1;
                }

                while (takeSlots > 0) {
                    line.getValue().occupySlot(freeSlots.get(0));
                    freeSlots.remove(0);
                    takeSlots -= 1;
                }

                Set<Integer> taken = new HashSet<>();

                outputBlockedSlots.forEach(slot -> {
                    ItemStack outStack = inventory.get(slot + tier.processes);
                    ItemStack recipeOutStack = line.getValue().getOutput();

                    if (taken.contains(slot))
                        return;

                    if (outStack.isItemEqual(recipeOutStack) || recipeOutStack.isEmpty()) {
                        taken.add(slot);
                        line.getValue().occupySlot(slot);
                    }
                });

            }

            for (Map.Entry<String, ProcessDescription> line : recipeSlots.entrySet()) {
                int total = line.getValue().getOccupiedSlots().stream()
                        .map(v -> inventory.get(v).getCount())
                        .reduce(0, Integer::sum);
                int count = line.getValue().getOccupiedSlots().size();

                if (count == 0)
                    continue;

                int minCount = line.getValue().getInput().getCount();

                int itemsPerSlot = total / count;
                int excessItems = total - (itemsPerSlot * count);

                for (Integer slot : line.getValue().getOccupiedSlots()) {
                    int actualCount = itemsPerSlot;

                    if (excessItems > 0) {
                        excessItems -= 1;
                        actualCount += 1;
                    }

                    actualCount = Math.max(actualCount, minCount);

                    if (total - actualCount < minCount) {
                        actualCount = total;
                    }

                    inventory.set(slot, StackUtils.size(line.getValue().getInput(), actualCount));

                    total -= actualCount;
                }
            }
        }
    }

    public ItemStack getRecipeOutput(MachineRecipe recipe) {
        if (recipe.recipeOutput instanceof ItemStackOutput) {
            return ((ItemStackOutput) recipe.recipeOutput).output;
        } else if (recipe.recipeOutput instanceof ChanceOutput) {
            return ((ChanceOutput) recipe.recipeOutput).primaryOutput;
        } else if (recipe.recipeOutput instanceof PressurizedOutput) {
            return ((PressurizedOutput) recipe.recipeOutput).getItemOutput();
        } else {
            return ItemStack.EMPTY;
        }
    }

    public ItemStack getRecipeInput(MachineRecipe recipe) {
        if (recipe.recipeInput instanceof ItemStackInput) {
            return ((ItemStackInput) recipe.recipeInput).ingredient;
        } else if (recipe.recipeInput instanceof AdvancedMachineInput) {
            AdvancedMachineInput advancedInput = (AdvancedMachineInput) recipe.recipeInput;
            return advancedInput.itemStack;
        } else if (recipe.recipeInput instanceof DoubleMachineInput) {
            DoubleMachineInput doubleMachineInput = (DoubleMachineInput) recipe.recipeInput;
            return doubleMachineInput.itemStack;
        } else if (recipe.recipeInput instanceof InfusionInput) {
            InfusionInput infusionInput = (InfusionInput) recipe.recipeInput;
            return infusionInput.inputStack;
        } else {
            return ItemStack.EMPTY;
        }
    }

    public MachineRecipe getSlotRecipe(int slotID, ItemStack fallbackInput) {
        int process = getOperation(slotID);
        // cached recipe may be invalid
        MachineRecipe cached = cachedRecipe[process];
        ItemStack extra = inventory.get(4);
        if (cached == null) {
            cached = recipeType.getAnyRecipe(fallbackInput, extra, gasTank.getGasType(), infuseStored);
            if (cached == null) { // We have not enough input probably
                cached = recipeType.getAnyRecipe(StackUtils.size(fallbackInput, fallbackInput.getMaxStackSize()), extra,
                        gasTank.getGasType(), infuseStored);
            }
        } else {
            ItemStack recipeInput = ItemStack.EMPTY;
            boolean secondaryMatch = true;
            if (cached.recipeInput instanceof ItemStackInput) {
                recipeInput = ((ItemStackInput) cached.recipeInput).ingredient;
            } else if (cached.recipeInput instanceof AdvancedMachineInput) {
                AdvancedMachineInput advancedInput = (AdvancedMachineInput) cached.recipeInput;
                recipeInput = advancedInput.itemStack;
                secondaryMatch = gasTank.getGasType() == null || advancedInput.gasType == gasTank.getGasType();
            } else if (cached.recipeInput instanceof DoubleMachineInput) {
                DoubleMachineInput doubleMachineInput = (DoubleMachineInput) cached.recipeInput;
                recipeInput = doubleMachineInput.itemStack;
                secondaryMatch = extra.isEmpty() || ItemStack.areItemsEqual(doubleMachineInput.extraStack, extra);
            } else if (cached.recipeInput instanceof InfusionInput) {
                InfusionInput infusionInput = (InfusionInput) cached.recipeInput;
                recipeInput = infusionInput.inputStack;
                secondaryMatch = infuseStored.getAmount() == 0
                        || infuseStored.getType() == infusionInput.infuse.getType();
            }
            // If there is no cached item input or it doesn't match our fallback
            // then it is an out of date cache so we compare against the new one
            // and update the cache while we are at it
            if (recipeInput.isEmpty() || !secondaryMatch || !ItemStack.areItemsEqual(recipeInput, fallbackInput)) {
                cached = recipeType.getAnyRecipe(fallbackInput, extra, gasTank.getGasType(), infuseStored);
            }
        }

        return cached;
    }

    /**
     * Checks if the cached recipe (or recipe for current factory if the cache is
     * out of date) can produce a specific output.
     *
     * @param slotID        Slot ID to grab the cached recipe of.
     * @param fallbackInput Used if the cached recipe is null or to validate the
     *                      cached recipe is not out of date.
     * @param output        The output we want.
     * @param updateCache   True to make the cached recipe get updated if it is out
     *                      of date.
     * @return True if the recipe produces the given output.
     */
    public boolean inputProducesOutput(int slotID, ItemStack fallbackInput, ItemStack output, boolean updateCache) {
        if (output.isEmpty()) {
            return true;
        }
        int process = getOperation(slotID);
        // cached recipe may be invalid
        MachineRecipe cached = cachedRecipe[process];
        ItemStack extra = inventory.get(4);
        if (cached == null) {
            cached = recipeType.getAnyRecipe(fallbackInput, extra, gasTank.getGasType(), infuseStored);
            if (updateCache) {
                cachedRecipe[process] = cached;
            }
        } else {
            ItemStack recipeInput = ItemStack.EMPTY;
            boolean secondaryMatch = true;
            if (cached.recipeInput instanceof ItemStackInput) {
                recipeInput = ((ItemStackInput) cached.recipeInput).ingredient;
            } else if (cached.recipeInput instanceof AdvancedMachineInput) {
                AdvancedMachineInput advancedInput = (AdvancedMachineInput) cached.recipeInput;
                recipeInput = advancedInput.itemStack;
                secondaryMatch = gasTank.getGasType() == null || advancedInput.gasType == gasTank.getGasType();
            } else if (cached.recipeInput instanceof DoubleMachineInput) {
                DoubleMachineInput doubleMachineInput = (DoubleMachineInput) cached.recipeInput;
                recipeInput = doubleMachineInput.itemStack;
                secondaryMatch = extra.isEmpty() || ItemStack.areItemsEqual(doubleMachineInput.extraStack, extra);
            } /*
               * else if (cached.recipeInput instanceof CultivateMachineInput) {
               * CultivateMachineInput cultivateMachineInput = (CultivateMachineInput)
               * cached.recipeInput;
               * recipeInput = cultivateMachineInput.itemStack;
               * secondaryMatch = (extra.isEmpty() ||
               * ItemStack.areItemsEqual(cultivateMachineInput.extraStack,
               * extra))||(gasTank.getGasType() == null || cultivateMachineInput.gasType ==
               * gasTank.getGasType());
               * }
               *//*
                  * else if (cached.recipeInput instanceof PressurizedInput) {
                  * PressurizedInput pressurizedInput = (PressurizedInput) cached.recipeInput;
                  * recipeInput = pressurizedInput.getSolid();
                  * secondaryMatch = gasTank.getGas() == null ||
                  * gasTank.getGas().isGasEqual(pressurizedInput.getGas());
                  * //TODO: Handle fluid for secondary matching if we ever have a PRC factory
                  * pressurizedInput.getFluid();
                  * }
                  */ else if (cached.recipeInput instanceof InfusionInput) {
                InfusionInput infusionInput = (InfusionInput) cached.recipeInput;
                recipeInput = infusionInput.inputStack;
                secondaryMatch = infuseStored.getAmount() == 0
                        || infuseStored.getType() == infusionInput.infuse.getType();
            }
            // If there is no cached item input or it doesn't match our fallback
            // then it is an out of date cache so we compare against the new one
            // and update the cache while we are at it
            if (recipeInput.isEmpty() || !secondaryMatch || !ItemStack.areItemsEqual(recipeInput, fallbackInput)) {
                cached = recipeType.getAnyRecipe(fallbackInput, extra, gasTank.getGasType(), infuseStored);
                if (updateCache) {
                    cachedRecipe[process] = cached;
                }
            }
        }
        // If there is no recipe found
        if (cached != null) {
            ItemStack recipeOutput = ItemStack.EMPTY;
            if (cached.recipeOutput instanceof ItemStackOutput) {
                recipeOutput = ((ItemStackOutput) cached.recipeOutput).output;
            } /*
               * else if (cached.recipeOutput instanceof PressurizedOutput) {
               * //TODO: uncomment if we add a PRC factory
               * recipeOutput = ((PressurizedOutput) cached.recipeOutput).getItemOutput();
               * }
               */
            if (!recipeOutput.isEmpty()) {
                return ItemStack.areItemsEqual(recipeOutput, output);
            }
        }
        return true;
    }

    public double getSecondaryEnergyPerTick(RecipeType type) {
        if (tier == FactoryTier.CREATIVE) {
            return 0;
        } else {
            return MekanismUtils.getSecondaryEnergyPerTickMean(this, type.getSecondaryEnergyPerTick());
        }
    }

    @Nullable
    public GasStack getItemGas(ItemStack itemStack) {
        if (recipeType.getFuelType() == MachineFuelType.ADVANCED || recipeType
                .getFuelType() == MachineFuelType.FARM /* || recipeType.getFuelType() ==MachineFuelType.CULTIVATE */) {
            return GasConversionHandler.getItemGas(itemStack, gasTank, recipeType::isValidGas);
        }
        return null;
    }

    public void handleSecondaryFuel() {
        ItemStack extra = inventory.get(4);
        if (!extra.isEmpty()) {
            if (recipeType.getFuelType() == MachineFuelType.ADVANCED && gasTank.getNeeded() > 0) {
                GasStack gasStack = getItemGas(extra);
                if (gasStack != null) {
                    Gas gas = gasStack.getGas();
                    if (gasTank.canReceive(gas) && gasTank.getNeeded() >= gasStack.amount) {
                        if (extra.getItem() instanceof IGasItem) {
                            IGasItem item = (IGasItem) extra.getItem();
                            gasTank.receive(item.removeGas(extra, gasStack.amount), true);
                        } else {
                            gasTank.receive(gasStack, true);
                            if (tier != FactoryTier.CREATIVE) {
                                extra.shrink(1);
                            }
                        }
                    }
                }
            } else if (recipeType.getFuelType() == MachineFuelType.FARM && gasTank.getNeeded() > 0) {
                GasStack gasStack = getItemGas(extra);
                if (gasStack != null) {
                    Gas gas = gasStack.getGas();
                    if (gasTank.canReceive(gas) && gasTank.getNeeded() >= gasStack.amount) {
                        if (extra.getItem() instanceof IGasItem) {
                            IGasItem item = (IGasItem) extra.getItem();
                            gasTank.receive(item.removeGas(extra, gasStack.amount), true);
                        } else {
                            gasTank.receive(gasStack, true);
                            if (tier != FactoryTier.CREATIVE) {
                                extra.shrink(1);
                            }
                        }
                    }
                }
            } /*
               * else if (recipeType.getFuelType() == MachineFuelType.CULTIVATE &&
               * gasTank.getNeeded() > 0) {
               * GasStack gasStack = getItemGas(extra);
               * if (gasStack != null) {
               * Gas gas = gasStack.getGas();
               * if (gasTank.canReceive(gas) && gasTank.getNeeded() >= gasStack.amount) {
               * if (extra.getItem() instanceof IGasItem) {
               * IGasItem item = (IGasItem) extra.getItem();
               * gasTank.receive(item.removeGas(extra, gasStack.amount), true);
               * } else {
               * gasTank.receive(gasStack, true);
               * extra.shrink(1);
               * }
               * }
               * }
               * }
               */else if (recipeType == RecipeType.INFUSING) {
                InfuseObject pendingInfusionInput = InfuseRegistry.getObject(extra);
                if (pendingInfusionInput != null) {
                    if (infuseStored.getType() == null || infuseStored.getType() == pendingInfusionInput.type) {
                        if (infuseStored.getAmount() + pendingInfusionInput.stored <= maxInfuse) {
                            infuseStored.increase(pendingInfusionInput);
                            if (tier != FactoryTier.CREATIVE) {
                                extra.shrink(1);
                            }
                        }
                    }
                }
            }
        }
    }

    public ItemStack getMachineStack() {
        return recipeType.getStack();
    }

    @Override
    public boolean canExtractItem(int slotID, @Nonnull ItemStack itemstack, @Nonnull EnumFacing side) {
        if (slotID == 1) {
            return ChargeUtils.canBeOutputted(itemstack, false);
        } else if (tier == FactoryTier.BASIC && slotID >= 8 && slotID <= 10) {
            return true;
        } else if (tier == FactoryTier.ADVANCED && slotID >= 10 && slotID <= 14) {
            return true;
        } else if (tier == FactoryTier.ELITE && slotID >= 12 && slotID <= 18) {
            return true;
        } else if (tier == FactoryTier.ULTIMATE && slotID >= 14 && slotID <= 22) {
            return true;
        } else if (tier == FactoryTier.CREATIVE && slotID >= 16 && slotID <= 26) {
            return true;
        } else
            return recipeType.getFuelType() == MachineFuelType.CHANCE
                    || recipeType.getFuelType() == MachineFuelType.FARM && slotID == 4;
    }

    @Override
    public boolean canInsertItem(int slotID, @Nonnull ItemStack itemstack, @Nonnull EnumFacing side) {
        if (slotID == 1) {
            return ChargeUtils.canBeDischarged(itemstack);
        } else if (isInputSlot(slotID)) {
            return inputProducesOutput(slotID, itemstack, inventory.get(tier.processes + slotID), false);
        }
        // TODO: Only allow inserting into extra slot if it can go in
        return super.canInsertItem(slotID, itemstack, side);
    }

    private boolean isInputSlot(int slotID) {
        return slotID >= 5 && (tier == FactoryTier.BASIC ? slotID <= 7
                : tier == FactoryTier.ADVANCED ? slotID <= 9
                        : tier == FactoryTier.ELITE ? slotID <= 11
                                : tier == FactoryTier.ULTIMATE ? slotID <= 13
                                        : tier == FactoryTier.CREATIVE && slotID <= 15);
    }

    @Override
    public boolean isItemValidForSlot(int slotID, @Nonnull ItemStack itemstack) {
        if (tier == FactoryTier.BASIC) {
            if (slotID >= 8 && slotID <= 10) {
                return false;
            } else if (slotID >= 5 && slotID <= 7) {
                if (recipeType.getFuelType() == MachineFuelType.ADVANCED
                        || recipeType.getFuelType() == MachineFuelType.FARM
                        || recipeType == RecipeType.INFUSING
                        || recipeType.getFuelType() == MachineFuelType.CHANCE
                        || recipeType.getFuelType() == MachineFuelType.DOUBLE) {
                    return recipeType.getAnyRecipe(itemstack, inventory.get(4), gasTank.getGasType(),
                            infuseStored) != null;

                } else
                    return recipeType.getRecipe(itemstack) != null;
            }
        } else if (tier == FactoryTier.ADVANCED) {
            if (slotID >= 10 && slotID <= 14) {
                return false;
            } else if (slotID >= 5 && slotID <= 9) {
                if (recipeType.getFuelType() == MachineFuelType.ADVANCED
                        || recipeType.getFuelType() == MachineFuelType.FARM
                        || recipeType == RecipeType.INFUSING
                        || recipeType.getFuelType() == MachineFuelType.CHANCE
                        || recipeType.getFuelType() == MachineFuelType.DOUBLE) {
                    return recipeType.getAnyRecipe(itemstack, inventory.get(4), gasTank.getGasType(),
                            infuseStored) != null;
                } else
                    return recipeType.getRecipe(itemstack) != null;
            }
        } else if (tier == FactoryTier.ELITE) {
            if (slotID >= 12 && slotID <= 18) {
                return false;
            } else if (slotID >= 5 && slotID <= 11) {
                if (recipeType.getFuelType() == MachineFuelType.ADVANCED
                        || recipeType.getFuelType() == MachineFuelType.FARM
                        || recipeType == RecipeType.INFUSING
                        || recipeType.getFuelType() == MachineFuelType.CHANCE
                        || recipeType.getFuelType() == MachineFuelType.DOUBLE) {
                    return recipeType.getAnyRecipe(itemstack, inventory.get(4), gasTank.getGasType(),
                            infuseStored) != null;
                } else
                    return recipeType.getRecipe(itemstack) != null;
            }
        } else if (tier == FactoryTier.ULTIMATE) {
            if (slotID >= 14 && slotID <= 22) {
                return false;
            } else if (slotID >= 5 && slotID <= 13) {
                if (recipeType.getFuelType() == MachineFuelType.ADVANCED
                        || recipeType.getFuelType() == MachineFuelType.FARM
                        || recipeType == RecipeType.INFUSING
                        || recipeType.getFuelType() == MachineFuelType.CHANCE
                        || recipeType.getFuelType() == MachineFuelType.DOUBLE) {
                    return recipeType.getAnyRecipe(itemstack, inventory.get(4), gasTank.getGasType(),
                            infuseStored) != null;
                } else
                    return recipeType.getRecipe(itemstack) != null;
            }
        } else if (tier == FactoryTier.CREATIVE) {
            if (slotID >= 16 && slotID <= 26) {
                return false;
            } else if (slotID >= 5 && slotID <= 15) {
                if (recipeType.getFuelType() == MachineFuelType.ADVANCED
                        || recipeType.getFuelType() == MachineFuelType.FARM
                        || recipeType == RecipeType.INFUSING
                        || recipeType.getFuelType() == MachineFuelType.CHANCE
                        || recipeType.getFuelType() == MachineFuelType.DOUBLE) {
                    return recipeType.getAnyRecipe(itemstack, inventory.get(4), gasTank.getGasType(),
                            infuseStored) != null;
                } else
                    return recipeType.getRecipe(itemstack) != null;
            }
        }

        if (slotID == 0) {
            return itemstack.getItem() == MekanismItems.SpeedUpgrade
                    || itemstack.getItem() == MekanismItems.EnergyUpgrade;
        } else if (slotID == 1) {
            return ChargeUtils.canBeDischarged(itemstack);
        } else if (slotID == 4) {
            if (recipeType.getFuelType() == MachineFuelType.ADVANCED) {
                return getItemGas(itemstack) != null;
            } else if (recipeType.getFuelType() == MachineFuelType.FARM) {
                return getItemGas(itemstack) != null;
            } else if (recipeType.getFuelType() == MachineFuelType.DOUBLE) {
                return recipeType.hasRecipeForExtra(itemstack);
            } /*
               * else if (recipeType.getFuelType() == MachineFuelType.CULTIVATE) {
               * return recipeType.hasRecipeForExtra(itemstack) || getItemGas(itemstack) !=
               * null;
               * }
               */else if (recipeType == RecipeType.INFUSING) {
                return InfuseRegistry.getObject(itemstack) != null && (infuseStored.getType() == null
                        || infuseStored.getType() == InfuseRegistry.getObject(itemstack).type);
            }
        }
        return false;
    }

    public int getScaledProgress(int i, int process) {
        return progress[process] * i / ticksRequired;
    }

    public int getScaledInfuseLevel(int i) {
        return infuseStored.getAmount() * i / maxInfuse;
    }

    public int getScaledGasLevel(int i) {
        return gasTank.getStored() * i / gasTank.getMaxGas();
    }

    public int getScaledRecipeProgress(int i) {

        return recipeTicks * i / RECIPE_TICKS_REQUIRED;
    }

    public boolean canOperate(int inputSlot, int outputSlot) {
        if (inventory.get(inputSlot).isEmpty()) {
            return false;
        }

        int process = getOperation(inputSlot);

        if (recipeType.getFuelType() == MachineFuelType.ADVANCED) {
            if (cachedRecipe[process] instanceof AdvancedMachineRecipe
                    && ((AdvancedMachineRecipe) cachedRecipe[process]).inputMatches(inventory, inputSlot, gasTank,
                            secondaryEnergyThisTick)) {
                return ((AdvancedMachineRecipe) cachedRecipe[process]).canOperate(inventory, inputSlot, outputSlot,
                        gasTank, secondaryEnergyThisTick);
            }
            AdvancedMachineRecipe<?> recipe = recipeType.getRecipe(inventory.get(inputSlot), gasTank.getGasType());
            cachedRecipe[process] = recipe;
            return recipe != null
                    && recipe.canOperate(inventory, inputSlot, outputSlot, gasTank, secondaryEnergyThisTick);
        } else if (recipeType.getFuelType() == MachineFuelType.FARM) {
            if (cachedRecipe[process] instanceof FarmMachineRecipe && ((FarmMachineRecipe) cachedRecipe[process])
                    .inputMatches(inventory, inputSlot, gasTank, secondaryEnergyThisTick)) {
                return ((FarmMachineRecipe) cachedRecipe[process]).canOperate(inventory, inputSlot, gasTank,
                        secondaryEnergyThisTick, outputSlot, 4);
            }
            FarmMachineRecipe<?> recipe = recipeType.getFarmRecipe(inventory.get(inputSlot), gasTank.getGasType());
            cachedRecipe[process] = recipe;
            return recipe != null
                    && recipe.canOperate(inventory, inputSlot, gasTank, secondaryEnergyThisTick, outputSlot, 4);
        } else if (recipeType.getFuelType() == MachineFuelType.DOUBLE) {
            if (cachedRecipe[process] instanceof DoubleMachineRecipe
                    && ((DoubleMachineRecipe) cachedRecipe[process]).inputMatches(inventory, inputSlot, 4)) {
                return ((DoubleMachineRecipe) cachedRecipe[process]).canOperate(inventory, inputSlot, 4, outputSlot);
            }
            DoubleMachineRecipe<?> recipe = recipeType.getRecipe(inventory.get(inputSlot), inventory.get(4));
            cachedRecipe[process] = recipe;
            return recipe != null && recipe.canOperate(inventory, inputSlot, 4, outputSlot);
        } /*
           * else if (recipeType.getFuelType() == MachineFuelType.CULTIVATE) {
           * if (cachedRecipe[process] instanceof CultivateMachineRecipe &&
           * ((CultivateMachineRecipe) cachedRecipe[process]).inputMatches(inventory,
           * inputSlot, 4, gasTank, secondaryEnergyThisTick)) {
           * return ((CultivateMachineRecipe) cachedRecipe[process]).canOperate(inventory,
           * inputSlot, 4, gasTank,secondaryEnergyThisTick,outputSlot);
           * }
           * CultivateMachineRecipe<?> recipe =
           * recipeType.getCultivateRecipe(inventory.get(inputSlot),
           * inventory.get(4),gasTank.getGasType());
           * cachedRecipe[process] = recipe;
           * return recipe != null && recipe.canOperate(inventory, inputSlot, 4,
           * gasTank,secondaryEnergyThisTick,outputSlot);
           * }
           */else if (recipeType.getFuelType() == MachineFuelType.CHANCE) {
            if (cachedRecipe[process] instanceof ChanceMachineRecipe
                    && ((ChanceMachineRecipe) cachedRecipe[process]).inputMatches(inventory, inputSlot)) {
                return ((ChanceMachineRecipe) cachedRecipe[process]).canOperate(inventory, inputSlot, outputSlot, 4);
            }
            ChanceMachineRecipe<?> recipe = recipeType.getChanceRecipe(inventory.get(inputSlot));
            cachedRecipe[process] = recipe;
            return recipe != null && recipe.canOperate(inventory, inputSlot, outputSlot, 4);
        }

        if (recipeType == RecipeType.INFUSING) {
            if (cachedRecipe[process] instanceof MetallurgicInfuserRecipe
                    && ((MetallurgicInfuserRecipe) cachedRecipe[process]).inputMatches(inventory, inputSlot,
                            infuseStored)) {
                return ((MetallurgicInfuserRecipe) cachedRecipe[process]).canOperate(inventory, inputSlot, outputSlot,
                        infuseStored);
            }
            InfusionInput input = new InfusionInput(infuseStored, inventory.get(inputSlot));
            MetallurgicInfuserRecipe recipe = RecipeHandler.getMetallurgicInfuserRecipe(input);
            cachedRecipe[process] = recipe;
            if (recipe == null) {
                return false;
            }
            return recipe.canOperate(inventory, inputSlot, outputSlot, infuseStored);
        }

        if (cachedRecipe[process] instanceof BasicMachineRecipe
                && ((BasicMachineRecipe) cachedRecipe[process]).inputMatches(inventory, inputSlot)) {
            return ((BasicMachineRecipe) cachedRecipe[process]).canOperate(inventory, inputSlot, outputSlot);
        }
        BasicMachineRecipe<?> recipe = recipeType.getRecipe(inventory.get(inputSlot));
        cachedRecipe[process] = recipe;
        return recipe != null && recipe.canOperate(inventory, inputSlot, outputSlot);

    }

    public void operate(int inputSlot, int outputSlot) {
        if (!canOperate(inputSlot, outputSlot)) {
            return;
        }
        int process = getOperation(inputSlot);
        if (cachedRecipe[process] == null) {// should never happen, but cant be too sure.
            Mekanism.logger.debug("cachedRecipe was null, but we were asked to operate anyway?! {} @ {}", this,
                    this.pos);
            return;
        }

        if (recipeType.getFuelType() == MachineFuelType.ADVANCED
                && cachedRecipe[process] instanceof AdvancedMachineRecipe) {
            AdvancedMachineRecipe<?> recipe = (AdvancedMachineRecipe<?>) cachedRecipe[process];
            recipe.operate(inventory, inputSlot, outputSlot, gasTank, secondaryEnergyThisTick);
        } else if (recipeType.getFuelType() == MachineFuelType.DOUBLE
                && cachedRecipe[process] instanceof DoubleMachineRecipe) {
            DoubleMachineRecipe<?> recipe = (DoubleMachineRecipe<?>) cachedRecipe[process];
            recipe.operate(inventory, inputSlot, 4, outputSlot);
        } /*
           * else if (recipeType.getFuelType() == MachineFuelType.CULTIVATE &&
           * cachedRecipe[process] instanceof CultivateMachineRecipe) {
           * CultivateMachineRecipe<?> recipe = (CultivateMachineRecipe<?>)
           * cachedRecipe[process];
           * recipe.operate(inventory, inputSlot, 4 ,gasTank,
           * secondaryEnergyThisTick,outputSlot);
           * }
           */else if (recipeType.getFuelType() == MachineFuelType.CHANCE
                && cachedRecipe[process] instanceof ChanceMachineRecipe) {
            ChanceMachineRecipe<?> recipe = (ChanceMachineRecipe<?>) cachedRecipe[process];
            recipe.operate(inventory, inputSlot, outputSlot, 4);
        } else if (recipeType.getFuelType() == MachineFuelType.FARM
                && cachedRecipe[process] instanceof FarmMachineRecipe) {
            FarmMachineRecipe<?> recipe = (FarmMachineRecipe<?>) cachedRecipe[process];
            recipe.operate(inventory, inputSlot, gasTank, secondaryEnergyThisTick, outputSlot, 4);
        } else if (recipeType == RecipeType.INFUSING && cachedRecipe[process] instanceof MetallurgicInfuserRecipe) {
            MetallurgicInfuserRecipe recipe = (MetallurgicInfuserRecipe) cachedRecipe[process];
            recipe.output(inventory, inputSlot, outputSlot, infuseStored);
        } else {
            BasicMachineRecipe<?> recipe = (BasicMachineRecipe<?>) cachedRecipe[process];
            recipe.operate(inventory, inputSlot, outputSlot);
        }

        markDirty();
    }

    @Override
    public void handlePacketData(ByteBuf dataStream) {
        if (FMLCommonHandler.instance().getEffectiveSide().isServer()) {
            int type = dataStream.readInt();
            if (type == 0) {
                sorting = !sorting;
            } else if (type == 1) {
                gasTank.setGas(null);
                infuseStored.setEmpty();
            }
            return;
        }

        super.handlePacketData(dataStream);

        if (FMLCommonHandler.instance().getEffectiveSide().isClient()) {
            RecipeType oldRecipe = recipeType;
            recipeType = RecipeType.values()[dataStream.readInt()];
            upgradeComponent.setSupported(Upgrade.GAS, recipeType.fuelEnergyUpgrades());
            recipeTicks = dataStream.readInt();
            sorting = dataStream.readBoolean();
            upgraded = dataStream.readBoolean();
            lastUsage = dataStream.readDouble();
            int amount = dataStream.readInt();
            if (amount > 0) {
                infuseStored.setAmount(amount);
                infuseStored.setType(InfuseRegistry.get(PacketHandler.readString(dataStream)));
            } else {
                infuseStored.setEmpty();
            }

            if (recipeType != oldRecipe) {
                setRecipeType(recipeType);
                if (!upgraded) {
                    MekanismUtils.updateBlock(world, getPos());
                }
            }

            for (int i = 0; i < tier.processes; i++) {
                progress[i] = dataStream.readInt();
            }
            TileUtils.readTankData(dataStream, gasTank);
            if (upgraded) {
                markDirty();
                MekanismUtils.updateBlock(world, getPos());
                upgraded = false;
            }
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound nbtTags) {
        super.readFromNBT(nbtTags);
        setRecipeType(RecipeType.values()[nbtTags.getInteger("recipeType")]);
        upgradeComponent.setSupported(Upgrade.GAS, recipeType.fuelEnergyUpgrades());
        recipeTicks = nbtTags.getInteger("recipeTicks");
        sorting = nbtTags.getBoolean("sorting");
        int amount = nbtTags.getInteger("infuseStored");
        if (amount != 0) {
            infuseStored.setAmount(amount);
            infuseStored.setType(InfuseRegistry.get(nbtTags.getString("type")));
        }
        for (int i = 0; i < tier.processes; i++) {
            progress[i] = nbtTags.getInteger("progress" + i);
        }
        gasTank.read(nbtTags.getCompoundTag("gasTank"));
        GasUtils.clearIfInvalid(gasTank, recipeType::isValidGas);
    }

    @Nonnull
    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbtTags) {
        super.writeToNBT(nbtTags);
        nbtTags.setInteger("recipeType", recipeType.ordinal());
        nbtTags.setInteger("recipeTicks", recipeTicks);
        nbtTags.setBoolean("sorting", sorting);
        if (infuseStored.getType() != null) {
            nbtTags.setString("type", infuseStored.getType().name);
            nbtTags.setInteger("infuseStored", infuseStored.getAmount());
        } else {
            nbtTags.setString("type", "null");
        }
        for (int i = 0; i < tier.processes; i++) {
            nbtTags.setInteger("progress" + i, progress[i]);
        }
        nbtTags.setTag("gasTank", gasTank.write(new NBTTagCompound()));
        return nbtTags;
    }

    @Override
    public TileNetworkList getNetworkedData(TileNetworkList data) {
        super.getNetworkedData(data);

        data.add(recipeType.ordinal());
        data.add(recipeTicks);
        data.add(sorting);
        data.add(upgraded);
        data.add(lastUsage);

        data.add(infuseStored.getAmount());
        if (infuseStored.getAmount() > 0) {
            data.add(infuseStored.getType().name);
        }

        data.add(progress);
        TileUtils.addTankData(data, gasTank);
        upgraded = false;
        return data;
    }

    public int getInputSlot(int operation) {
        return 5 + operation;
    }

    /* reverse of the above */
    private int getOperation(int inputSlot) {
        return inputSlot - 5;
    }

    public int getOutputSlot(int operation) {
        return 5 + tier.processes + operation;
    }

    @Nonnull
    @Override
    public String getName() {
        if (LangUtils
                .canLocalize("tile." + tier.getBaseTier().getName() + recipeType.getTranslationKey() + "Factory")) {
            return LangUtils
                    .localize("tile." + tier.getBaseTier().getName() + recipeType.getTranslationKey() + "Factory");
        }
        return tier.getBaseTier().getLocalizedName() + recipeType.getLocalizedName() + super.getName();
    }

    @Override
    public String[] getMethods() {
        return methods;
    }

    @Override
    public Object[] invoke(int method, Object[] arguments) throws NoSuchMethodException {
        switch (method) {
            case 0:
                return new Object[] { electricityStored };
            case 1:
                if (arguments[0] == null) {
                    return new Object[] { "Please provide a target operation." };
                }
                if (!(arguments[0] instanceof Double) && !(arguments[0] instanceof Integer)) {
                    return new Object[] { "Invalid characters." };
                }
                if ((Integer) arguments[0] < 0 || (Integer) arguments[0] > progress.length) {
                    return new Object[] { "No such operation found." };
                }
                return new Object[] { progress[(Integer) arguments[0]] };
            case 2:
                return new Object[] { facing };
            case 3:
                if (arguments[0] == null) {
                    return new Object[] { "Please provide a target operation." };
                }
                if (!(arguments[0] instanceof Double) && !(arguments[0] instanceof Integer)) {
                    return new Object[] { "Invalid characters." };
                }
                if ((Integer) arguments[0] < 0 || (Integer) arguments[0] > progress.length) {
                    return new Object[] { "No such operation found." };
                }
                return new Object[] {
                        canOperate(getInputSlot((Integer) arguments[0]), getOutputSlot((Integer) arguments[0])) };
            case 4:
                return new Object[] { getMaxEnergy() };
            case 5:
                return new Object[] { getMaxEnergy() - getEnergy() };
            default:
                throw new NoSuchMethodException();
        }
    }

    @Nonnull
    @Override
    public int[] getSlotsForFace(@Nonnull EnumFacing side) {
        return configComponent.getOutput(TransmissionType.ITEM, side, facing).availableSlots;
    }

    @Override
    public boolean canSetFacing(@Nonnull EnumFacing facing) {
        return facing != EnumFacing.DOWN && facing != EnumFacing.UP;
    }

    @Override
    public TileComponentConfig getConfig() {
        return configComponent;
    }

    @Override
    public EnumFacing getOrientation() {
        return facing;
    }

    @Override
    public TileComponentEjector getEjector() {
        return ejectorComponent;
    }

    @Override
    public int receiveGas(EnumFacing side, GasStack stack, boolean doTransfer) {
        if (canReceiveGas(side, stack.getGas())) {
            return gasTank.receive(stack, doTransfer);
        }
        return 0;
    }

    @Override
    public boolean canReceiveGas(EnumFacing side, Gas type) {
        if (configComponent.getOutput(TransmissionType.GAS, side, facing).hasSlot(0)) {
            return recipeType.canReceiveGas(side, type);
        }
        return false;
    }

    @Override
    public GasStack drawGas(EnumFacing side, int amount, boolean doTransfer) {
        return null;
    }

    @Override
    public boolean canDrawGas(EnumFacing side, Gas type) {
        return false;
    }

    @Nonnull
    @Override
    public GasTankInfo[] getTankInfo() {
        return new GasTankInfo[] { gasTank };
    }

    @Override
    public boolean hasCapability(@Nonnull Capability<?> capability, EnumFacing side) {
        if (isCapabilityDisabled(capability, side)) {
            return false;
        }
        return capability == Capabilities.GAS_HANDLER_CAPABILITY || capability == Capabilities.CONFIG_CARD_CAPABILITY
                || capability == Capabilities.SPECIAL_CONFIG_DATA_CAPABILITY || super.hasCapability(capability, side);
    }

    @Override
    public <T> T getCapability(@Nonnull Capability<T> capability, EnumFacing side) {
        if (isCapabilityDisabled(capability, side)) {
            return null;
        }
        if (capability == Capabilities.GAS_HANDLER_CAPABILITY || capability == Capabilities.CONFIG_CARD_CAPABILITY
                || capability == Capabilities.SPECIAL_CONFIG_DATA_CAPABILITY) {
            return (T) this;
        }
        return super.getCapability(capability, side);
    }

    @Override
    public boolean isCapabilityDisabled(@Nonnull Capability<?> capability, EnumFacing side) {
        if (configComponent.isCapabilityDisabled(capability, side, facing)) {
            return true;
        } else if (capability == Capabilities.GAS_HANDLER_CAPABILITY) {
            // If the gas capability is not disabled, check if this machine even actually
            // supports gas
            return !recipeType.supportsGas();
        }
        return super.isCapabilityDisabled(capability, side);
    }

    @Override
    public void recalculateUpgradables(Upgrade upgrade) {
        super.recalculateUpgradables(upgrade);
        switch (upgrade) {
            case ENERGY:
                energyPerTick = MekanismUtils.getEnergyPerTick(this, BASE_ENERGY_PER_TICK); // incorporate speed
                                                                                            // upgrades
                break;
            case GAS:
                secondaryEnergyPerTick = getSecondaryEnergyPerTick(recipeType);
                break;
            case SPEED:
                if (tier != FactoryTier.CREATIVE) {
                    ticksRequired = MekanismUtils.getTicks(this, BASE_TICKS_REQUIRED);
                } else {
                    ticksRequired = BASE_TICKS_REQUIRED;
                }
                energyPerTick = MekanismUtils.getEnergyPerTick(this, BASE_ENERGY_PER_TICK);
                secondaryEnergyPerTick = getSecondaryEnergyPerTick(recipeType);
                break;
            default:
                break;
        }
    }

    @Override
    public NBTTagCompound getConfigurationData(NBTTagCompound nbtTags) {
        nbtTags.setBoolean("sorting", sorting);
        return nbtTags;
    }

    @Override
    public void setConfigurationData(NBTTagCompound nbtTags) {
        sorting = nbtTags.getBoolean("sorting");
    }

    @Override
    public String getDataType() {
        return tier.getBaseTier().getLocalizedName() + recipeType.getLocalizedName() + super.getName();
    }

    @Override
    public void writeSustainedData(ItemStack itemStack) {
        infuseStored.writeSustainedData(itemStack);
        GasUtils.writeSustainedData(gasTank, itemStack);
    }

    @Override
    public void readSustainedData(ItemStack itemStack) {
        infuseStored.readSustainedData(itemStack);
        GasUtils.readSustainedData(gasTank, itemStack);
    }

    @Override
    public int getRedstoneLevel() {
        return Container.calcRedstoneFromInventory(this);
    }

    private static class ProcessDescription {
        private final ItemStack input;
        private final ItemStack output;
        private final Set<Integer> occupiedSlots;

        public ProcessDescription(ItemStack input, ItemStack output) {
            this.input = input;
            this.output = output;
            this.occupiedSlots = new HashSet<>();
        }

        public ItemStack getInput() {
            return input;
        }

        public ItemStack getOutput() {
            return output;
        }

        public Set<Integer> getOccupiedSlots() {
            return occupiedSlots;
        }

        public void occupySlot(Integer slot) {
            occupiedSlots.add(slot);
        }
    }
}