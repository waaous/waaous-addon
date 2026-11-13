package com.waaous.addon;

import com.waaous.addon.modules.QQGroupDetector;
import com.waaous.addon.modules.RallyFollow;
import com.waaous.addon.modules.SmartMiner;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class WaaousAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();

    /** waaous-addon 专属分类 */
    public static final Category CATEGORY = new Category("waaous-addon");

    @Override
    public void onInitialize() {
        LOG.info("Initializing waaous-addon");

        Modules.get().add(new SmartMiner());
        Modules.get().add(new QQGroupDetector());
        Modules.get().add(new RallyFollow());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.waaous.addon";
    }
}
