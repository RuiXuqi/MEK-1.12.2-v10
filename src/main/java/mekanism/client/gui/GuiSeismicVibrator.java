package mekanism.client.gui;

import java.util.Arrays;
import mekanism.client.gui.element.GuiEnergyInfo;
import mekanism.client.gui.element.GuiPowerBar;
import mekanism.client.gui.element.GuiRedstoneControl;
import mekanism.client.gui.element.GuiSlot;
import mekanism.client.gui.element.GuiSlot.SlotOverlay;
import mekanism.client.gui.element.GuiSlot.SlotType;
import mekanism.client.gui.element.tab.GuiSecurityTab;
import mekanism.common.config.MekanismConfig;
import mekanism.common.inventory.container.ContainerSeismicVibrator;
import mekanism.common.tile.TileEntitySeismicVibrator;
import mekanism.common.util.LangUtils;
import mekanism.common.util.MekanismUtils;
import mekanism.common.util.MekanismUtils.ResourceType;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public class GuiSeismicVibrator extends GuiMekanismTile<TileEntitySeismicVibrator> {

    public GuiSeismicVibrator(InventoryPlayer inventory, TileEntitySeismicVibrator tile) {
        super(tile, new ContainerSeismicVibrator(inventory, tile));
        ResourceLocation resource = getGuiLocation();
        addGuiElement(new GuiSecurityTab(this, tileEntity, resource));
        addGuiElement(new GuiRedstoneControl(this, tileEntity, resource));
        addGuiElement(new GuiPowerBar(this, tileEntity, resource, 164, 15));
        addGuiElement(new GuiEnergyInfo(() -> {
            String multiplier = MekanismUtils.getEnergyDisplay(MekanismConfig.current().usage.seismicVibrator.val());
            return Arrays.asList(LangUtils.localize("gui.using") + ": " + multiplier + "/t",
                  LangUtils.localize("gui.needed") + ": " + MekanismUtils.getEnergyDisplay(tileEntity.getMaxEnergy() - tileEntity.getEnergy()));
        }, this, resource));
        addGuiElement(new GuiSlot(SlotType.NORMAL, this, resource, 142, 34).with(SlotOverlay.POWER));
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        fontRenderer.drawString(tileEntity.getName(), 45, 6, 0x404040);
        fontRenderer.drawString(LangUtils.localize("container.inventory"), 8, (ySize - 96) + 2, 0x404040);
        fontRenderer.drawString(tileEntity.isActive ? LangUtils.localize("gui.vibrating") : LangUtils.localize("gui.idle"), 19, 26, 0x33ff99);
        fontRenderer.drawString(LangUtils.localize("gui.chunk") + ": " + (tileEntity.getPos().getX() >> 4) + ", " + (tileEntity.getPos().getZ() >> 4), 19, 35, 0x33ff99);
        super.drawGuiContainerForegroundLayer(mouseX, mouseY);
    }

    @Override
    protected ResourceLocation getGuiLocation() {
        return MekanismUtils.getResource(ResourceType.GUI, "GuiSeismicVibrator.png");
    }
}