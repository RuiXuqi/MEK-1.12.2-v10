package mekanism.client.gui;

import java.io.IOException;
import java.util.Set;
import javax.annotation.Nullable;
import mekanism.api.Coord4D;
import mekanism.client.gui.button.GuiButtonDisableableImage;
import mekanism.client.render.MekanismRenderer;
import mekanism.common.Mekanism;
import mekanism.common.Upgrade;
import mekanism.common.base.IUpgradeTile;
import mekanism.common.block.states.BlockStateMachine.MachineType;
import mekanism.common.inventory.container.ContainerUpgradeManagement;
import mekanism.common.network.PacketRemoveUpgrade.RemoveUpgradeMessage;
import mekanism.common.network.PacketSimpleGui.SimpleGuiMessage;
import mekanism.common.util.LangUtils;
import mekanism.common.util.MekanismUtils;
import mekanism.common.util.MekanismUtils.ResourceType;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public class GuiUpgradeManagement extends GuiMekanism {

    private GuiButton backButton;
    private GuiButton removeButton;
    private IUpgradeTile tileEntity;
    @Nullable
    private Upgrade selectedType;
    private boolean isDragging = false;
    private int dragOffset = 0;
    private int supportedIndex;
    private int delay;
    private float scroll;

    public GuiUpgradeManagement(InventoryPlayer inventory, IUpgradeTile tile) {
        super(new ContainerUpgradeManagement(inventory, tile));
        tileEntity = tile;
    }

    @Override
    public void initGui() {
        super.initGui();
        buttonList.clear();
        buttonList.add(backButton = new GuiButtonDisableableImage(0, guiLeft + 6, guiTop + 6, 14, 14, 176, 14, -14, getGuiLocation()));
        buttonList.add(removeButton = new GuiButtonDisableableImage(1, guiLeft + 136, guiTop + 57, 12, 12, 190, 12, -12, 12, getGuiLocation()));
        updateEnabledButtons();
    }

    @Override
    protected void actionPerformed(GuiButton guibutton) throws IOException {
        super.actionPerformed(guibutton);
        TileEntity tile = (TileEntity) tileEntity;
        if (guibutton.id == backButton.id) {
            int guiId = MachineType.get(tile.getBlockType(), tile.getBlockMetadata()).guiId;
            Mekanism.packetHandler.sendToServer(new SimpleGuiMessage(Coord4D.get(tile), 0, guiId));
        } else if (guibutton.id == removeButton.id) {
            if (selectedType != null) {
                Mekanism.packetHandler.sendToServer(new RemoveUpgradeMessage(Coord4D.get(tile), selectedType.ordinal()));
            }
        }
    }

    private boolean overUpgradeType(int xAxis, int yAxis, int xPos, int yPos) {
        return xAxis >= xPos && xAxis <= xPos + 58 && yAxis >= yPos && yAxis <= yPos + 12;
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (delay < 40) {
            delay++;
        } else {
            delay = 0;
            supportedIndex = ++supportedIndex % tileEntity.getComponent().getSupportedTypes().size();
        }
        updateEnabledButtons();
    }

    private void updateEnabledButtons() {
        removeButton.enabled = selectedType != null;
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        mc.renderEngine.bindTexture(getGuiLocation());
        drawTexturedModalRect(84, 8 + getScroll(), 202, 0, 4, 4);
        fontRenderer.drawString(LangUtils.localize("container.inventory"), 8, (ySize - 96) + 2, 0x404040);
        fontRenderer.drawString(LangUtils.localize("gui.upgrades.supported") + ":", 26, 59, 0x33ff99);
        if (selectedType == null) {
            renderText(LangUtils.localize("gui.upgrades.noSelection") + ".", 92, 8, 1.0F, true);
        } else {
            int amount = tileEntity.getComponent().getUpgrades(selectedType);
            renderText(selectedType.getName() + LangUtils.localize("gui.upgrade"), 92, 8, 1.0F, true);
            renderText(LangUtils.localize("gui.upgrades.amount") + ": " + amount + "/" + selectedType.getMax(), 92, 16, 1.0F, true);
            int text = 0;
            for (String s : selectedType.getInfo((TileEntity) tileEntity)) {
                renderText(s, 92, 24 + (6 * text++), 1.0F, true);
            }
        }
        if (!tileEntity.getComponent().getSupportedTypes().isEmpty()) {
            Upgrade[] supported = tileEntity.getComponent().getSupportedTypes().toArray(new Upgrade[0]);
            if (supported.length > supportedIndex) {
                renderUpgrade(supported[supportedIndex], 80, 57, 0.8F, true);
                fontRenderer.drawString(supported[supportedIndex].getName(), 96, 59, 0x33ff99);
            }
        }
        Upgrade[] upgrades = getCurrentUpgrades().toArray(new Upgrade[0]);
        int xAxis = mouseX - guiLeft;
        int yAxis = mouseY - guiTop;
        for (int i = 0; i < 4; i++) {
            int index = getUpgradeIndex() + i;
            if (index > upgrades.length - 1) {
                break;
            }
            Upgrade upgrade = upgrades[index];
            int xPos = 25;
            int yPos = 7 + (i * 12);
            fontRenderer.drawString(upgrade.getName(), xPos + 12, yPos + 2, 0x33ff99);
            renderUpgrade(upgrade, xPos + 2, yPos + 2, 0.5F, true);
            if (overUpgradeType(xAxis, yAxis, xPos, yPos)) {
                displayTooltips(MekanismUtils.splitTooltip(upgrade.getDescription(), upgrade.getStack()), xAxis, yAxis);
            }
        }

        super.drawGuiContainerForegroundLayer(mouseX, mouseY);
    }

    private void renderText(String text, int x, int y, float size, boolean scale) {
        GlStateManager.pushMatrix();
        GlStateManager.scale(size, size, size);
        fontRenderer.drawString(text, scale ? (int) ((1F / size) * x) : x, scale ? (int) ((1F / size) * y) : y, 0x33ff99);
        GlStateManager.popMatrix();
    }

    private void renderUpgrade(Upgrade type, int x, int y, float size, boolean scale) {
        if (scale) {
            renderItem(type.getStack(), (int) ((float) x / size), (int) ((float) y / size), size);
        } else {
            renderItem(type.getStack(), x, y, size);
        }
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(int xAxis, int yAxis) {
        super.drawGuiContainerBackgroundLayer(xAxis, yAxis);
        int displayInt = tileEntity.getComponent().getScaledUpgradeProgress(14);
        drawTexturedModalRect(guiLeft + 154, guiTop + 26, 176, 28, 10, displayInt);
        if (selectedType != null && tileEntity.getComponent().getUpgrades(selectedType) == 0) {
            selectedType = null;
        }
        Upgrade[] upgrades = getCurrentUpgrades().toArray(new Upgrade[0]);
        for (int i = 0; i < 4; i++) {
            int index = getUpgradeIndex() + i;
            if (index > upgrades.length - 1) {
                break;
            }
            Upgrade upgrade = upgrades[index];
            int xPos = 25;
            int yPos = 7 + (i * 12);
            int yRender;
            if (upgrade == selectedType) {
                yRender = 166 + 24;
            } else if (overUpgradeType(xAxis, yAxis, xPos, yPos)) {
                yRender = 166;
            } else {
                yRender = 166 + 12;
            }
            MekanismRenderer.color(upgrade.getColor(), 1.0F, 2.5F);
            drawTexturedModalRect(guiLeft + xPos, guiTop + yPos, 0, yRender, 58, 12);
            MekanismRenderer.resetColor();
        }
    }

    private Set<Upgrade> getCurrentUpgrades() {
        return tileEntity.getComponent().getInstalledTypes();
    }

    public int getScroll() {
        return Math.max(Math.min((int) (scroll * 42), 42), 0);
    }

    public int getUpgradeIndex() {
        if (getCurrentUpgrades().size() <= 4) {
            return 0;
        }
        return (int) ((getCurrentUpgrades().size() * scroll) - (4F / (float) getCurrentUpgrades().size()) * scroll);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long ticks) {
        super.mouseClickMove(mouseX, mouseY, button, ticks);
        if (isDragging) {
            int yAxis = mouseY - (height - ySize) / 2;
            scroll = Math.min(Math.max((float) (yAxis - 8 - dragOffset) / 42F, 0), 1);
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int type) {
        super.mouseReleased(mouseX, mouseY, type);
        if (type == 0 && isDragging) {
            dragOffset = 0;
            isDragging = false;
        }
    }

    @Override
    protected ResourceLocation getGuiLocation() {
        return MekanismUtils.getResource(ResourceType.GUI, "GuiUpgradeManagement.png");
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        super.mouseClicked(mouseX, mouseY, button);
        if (button == 0) {
            int xAxis = mouseX - guiLeft;
            int yAxis = mouseY - guiTop;
            if (xAxis >= 84 && xAxis <= 88 && yAxis >= getScroll() + 8 && yAxis <= getScroll() + 8 + 4) {
                if (getCurrentUpgrades().size() > 4) {
                    dragOffset = yAxis - (getScroll() + 8);
                    isDragging = true;
                } else {
                    scroll = 0;
                }
            }
            int counter = 0;
            for (Upgrade upgrade : getCurrentUpgrades()) {
                int xPos = 25;
                int yPos = 7 + (counter++ * 12);
                if (overUpgradeType(xAxis, yAxis, xPos, yPos)) {
                    selectedType = upgrade;
                    break;
                }
            }
        }
    }
}