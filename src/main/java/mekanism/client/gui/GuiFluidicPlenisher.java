package mekanism.client.gui;

import java.util.Arrays;
import mekanism.client.gui.element.GuiEnergyInfo;
import mekanism.client.gui.element.GuiPowerBar;
import mekanism.client.gui.element.GuiRedstoneControl;
import mekanism.client.gui.element.GuiSlot;
import mekanism.client.gui.element.GuiSlot.SlotOverlay;
import mekanism.client.gui.element.GuiSlot.SlotType;
import mekanism.client.gui.element.gauge.GuiFluidGauge;
import mekanism.client.gui.element.gauge.GuiGauge;
import mekanism.client.gui.element.tab.GuiSecurityTab;
import mekanism.client.gui.element.tab.GuiUpgradeTab;
import mekanism.common.inventory.container.ContainerFluidicPlenisher;
import mekanism.common.tile.TileEntityFluidicPlenisher;
import mekanism.common.util.LangUtils;
import mekanism.common.util.MekanismUtils;
import mekanism.common.util.MekanismUtils.ResourceType;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public class GuiFluidicPlenisher extends GuiMekanismTile<TileEntityFluidicPlenisher> {

    public GuiFluidicPlenisher(InventoryPlayer inventory, TileEntityFluidicPlenisher tile) {
        super(tile, new ContainerFluidicPlenisher(inventory, tile));
        ResourceLocation resource = getGuiLocation();
        addGuiElement(new GuiSlot(SlotType.NORMAL, this, resource, 27, 19));
        addGuiElement(new GuiSlot(SlotType.NORMAL, this, resource, 27, 50));
        addGuiElement(new GuiSlot(SlotType.POWER, this, resource, 142, 34).with(SlotOverlay.POWER));
        addGuiElement(new GuiPowerBar(this, tileEntity, resource, 164, 15));
        addGuiElement(new GuiFluidGauge(() -> tileEntity.fluidTank, GuiGauge.Type.STANDARD, this, resource, 6, 13));
        addGuiElement(new GuiEnergyInfo(() -> {
            String multiplier = MekanismUtils.getEnergyDisplay(tileEntity.energyPerTick);
            return Arrays.asList(LangUtils.localize("gui.using") + ": " + multiplier + "/t",
                  LangUtils.localize("gui.needed") + ": " + MekanismUtils.getEnergyDisplay(tileEntity.getMaxEnergy() - tileEntity.getEnergy()));
        }, this, resource));
        addGuiElement(new GuiSecurityTab(this, tileEntity, resource));
        addGuiElement(new GuiRedstoneControl(this, tileEntity, resource));
        addGuiElement(new GuiUpgradeTab(this, tileEntity, resource));
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        fontRenderer.drawString(tileEntity.getName(), (xSize / 2) - (fontRenderer.getStringWidth(tileEntity.getName()) / 2), 6, 0x404040);
        fontRenderer.drawString(LangUtils.localize("container.inventory"), 8, (ySize - 94) + 2, 0x404040);
        fontRenderer.drawString(MekanismUtils.getEnergyDisplay(tileEntity.getEnergy(), tileEntity.getMaxEnergy()), 51, 26, 0x33ff99);
        fontRenderer.drawString(LangUtils.localize("gui.finished") + ": " + LangUtils.transYesNo(tileEntity.finishedCalc), 51, 35, 0x33ff99);
        FluidStack fluid = tileEntity.fluidTank.getFluid();
        fontRenderer.drawString(fluid != null ? LangUtils.localizeFluidStack(fluid) + ": " + fluid.amount : LangUtils.localize("gui.noFluid"), 51, 44, 0x33ff99);
        super.drawGuiContainerForegroundLayer(mouseX, mouseY);
    }

    @Override
    protected ResourceLocation getGuiLocation() {
        return MekanismUtils.getResource(ResourceType.GUI, "GuiElectricPump.png");
    }
}