/*
 * Copyright (c) 2017 Adrian Siekierka
 *
 * This file is part of Unlimited Chisel Works.
 *
 * Unlimited Chisel Works is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Unlimited Chisel Works is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Unlimited Chisel Works.  If not, see <http://www.gnu.org/licenses/>.
 */

package pl.asie.ucw;

import com.google.common.base.Charsets;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.JsonUtils;
import net.minecraft.util.NonNullList;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.oredict.OreDictionary;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

@Mod(modid = UnlimitedChiselWorks.MODID, version = UnlimitedChiselWorks.VERSION)
public class UnlimitedChiselWorks {
    public static final String MODID = "unlimitedchiselworks";
    public static final String VERSION = "${version}";
    public static final Set<UCWBlockRule> BLOCK_RULES = new LinkedHashSet<>();
    protected static final Gson GSON = new Gson();
    private static Logger LOGGER;

    @SidedProxy(clientSide = "pl.asie.ucw.UCWProxyClient", serverSide = "pl.asie.ucw.UCWProxyCommon")
    private static UCWProxyCommon proxy;

    private void proposeRule(Path p) throws IOException {
        if (Files.isDirectory(p)) {
            for (Path pp : Files.newDirectoryStream(p)) {
                try {
                    proposeRule(pp);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        } else {
            BufferedReader reader = Files.newBufferedReader(p, Charsets.UTF_8);
            try {
                JsonObject json = JsonUtils.fromJson(GSON, reader, JsonObject.class);
                if (json != null) {
                    if (json.has("blocks")) {
                        for (JsonElement element : json.get("blocks").getAsJsonArray()) {
                            if (element.isJsonObject()) {
                                try {
                                    UCWBlockRule rule = new UCWBlockRule(element.getAsJsonObject());
                                    if (rule.isValid()) {
                                        if (BLOCK_RULES.contains(rule)) {
                                            LOGGER.warn("Duplicate rule found! " + rule);
                                        } else {
                                            BLOCK_RULES.add(rule);
                                        }
                                    }
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void findRules() {
        BLOCK_RULES.clear();

        for (ModContainer container : Loader.instance().getActiveModList()) {
            File file = container.getSource();
            try {
                if (file.exists()) {
                    if (file.isDirectory()) {
                        File f = new File(file, "assets/" + container.getModId() + "/ucwdefs");
                        if (f.exists() && f.isDirectory()) {
                            proposeRule(f.toPath());
                        }
                    } else {
                        FileSystem fs = FileSystems.newFileSystem(file.toPath(), null);
                        proposeRule(fs.getPath("assets/" + container.getModId() + "/ucwdefs"));
                    }
                }
            } catch (NoSuchFileException e) {
                // no problem with this one
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        LOGGER.info("Found " + BLOCK_RULES.size() + " rules.");
    }

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER = LogManager.getLogger(MODID);
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(proxy);
        proxy.preInit();
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public void registerBlocks(RegistryEvent.Register<Block> event) {
        findRules();

        for (UCWBlockRule rule : BLOCK_RULES) {
            rule.registerBlocks(event.getRegistry());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public void registerItems(RegistryEvent.Register<Item> event) {
        for (UCWBlockRule rule : BLOCK_RULES) {
            rule.registerItems(event.getRegistry());
        }
    }

    @EventHandler
    public void init(FMLInitializationEvent event) {
        for (UCWBlockRule rule : BLOCK_RULES) {
            for (int i = 0; i < rule.from.size(); i++) {
                IBlockState fromState = rule.from.get(i);
                if (fromState == null) continue;

                String groupName = rule.fromCount == 1 ? rule.group : rule.group + "_" + i;
                UCWCompatUtils.addChiselVariation(groupName, new ItemStack(fromState.getBlock(), 1, fromState.getBlock().damageDropped(fromState)));

                UCWObjectFactory factory = rule.objectFactories.get(i);
                NonNullList<ItemStack> stacks = NonNullList.create();
                factory.item.getSubItems(CreativeTabs.SEARCH, stacks);
                for (ItemStack stack : stacks) {
                    UCWCompatUtils.addChiselVariation(groupName, stack);
                }
            }
        }
    }

    @EventHandler
    public void postInit(FMLInitializationEvent event) {
        for (UCWBlockRule rule : BLOCK_RULES) {
            ItemStack stack = new ItemStack(rule.fromBlock, 1, OreDictionary.WILDCARD_VALUE);
            int[] ids = OreDictionary.getOreIDs(stack);
            if (ids.length > 0) {
                for (UCWObjectFactory factory : rule.objectFactories.valueCollection()) {
                    for (int i : ids) {
                        OreDictionary.registerOre(OreDictionary.getOreName(i), factory.block);
                    }
                }
            }
        }
    }

    @EventHandler
    public void onServerStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandUCWDebug());
    }
}
