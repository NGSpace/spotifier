package dev.ngspace.hudder.spotifier.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import dev.ngspace.hudder.spotifier.Spotifier;
import dev.ngspace.ngsmcconfig.api.NGSMCConfigBuilder;
import dev.ngspace.ngsmcconfig.options.IntNGSMCConfigOption;
import dev.ngspace.ngsmcconfig.options.StringNGSMCConfigOption;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class SpotifierConfigModmenu implements ModMenuApi {

	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return this::createConfigScreen;
	}
	
	public Screen createConfigScreen(Screen screen) {
		
		NGSMCConfigBuilder builder = new NGSMCConfigBuilder(screen);
		
		builder.setWriteOperation(()->{
			try {
				Spotifier.refreshAllTokens();
				SpotifierConfig.save();
			} catch (Exception e) {
				e.printStackTrace();
			}
		});
		
		var spotify = builder.createCategory(Component.translatable("spotifier.spotify"));
		
		spotify.addOption(StringNGSMCConfigOption.builder(SpotifierConfig.client_id,
				Component.translatable("spotifier.spotify.client_id"))
				.setHoverComponent(Component.translatable("spotifier.spotify.client_id.desc"))
				.setSaveOperation(s->SpotifierConfig.client_id = s)
				.setValidator(id->id.length()<10?Component.translatable("spotifier.spotify.client_id.short"):null)
				.build());
		spotify.addOption(IntNGSMCConfigOption.builder((int) SpotifierConfig.pull_rate,
				Component.translatable("spotifier.spotify.pull_rate"))
				.setHoverComponent(Component.translatable("spotifier.spotify.pull_rate.desc"))
				.setDefaultValue(1250)
				.setSaveOperation(s->SpotifierConfig.pull_rate = s)
				.setValidator(r->r<500?Component.translatable("spotifier.spotify.pull_rate.low"):null)
				.build());
		
		return builder.build();
	}
}
