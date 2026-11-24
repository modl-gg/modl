package gg.modl.minecraft.core.impl.menus.util;

import dev.simplix.cirrus.model.Click;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.PluginServices;
import gg.modl.minecraft.core.staff.action.AuthorizedStaff;
import gg.modl.minecraft.core.staff.action.StaffActor;
import gg.modl.minecraft.core.staff.action.StaffActors;
import gg.modl.minecraft.core.staff.action.StaffRequirement;

import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class StaffViewer {
    private final UUID uuid;
    private final StaffActor actor;

    public StaffViewer(Platform platform, UUID uuid) {
        this.uuid = uuid;
        this.actor = StaffActors.player(uuid, platform);
    }

    public boolean allows(StaffRequirement requirement) {
        return PluginServices.installed()
                .map(services -> services.getStaffActionGate().allows(actor, requirement))
                .orElse(false);
    }

    public Optional<AuthorizedStaff> authorizeAs(StaffRequirement requirement) {
        return PluginServices.installed()
                .flatMap(services -> services.getStaffActionGate().authorizeAs(actor, requirement));
    }

    public boolean authorize(StaffRequirement requirement) {
        return authorizeAs(requirement).isPresent();
    }

    public Consumer<Click> guard(StaffRequirement requirement, Consumer<Click> action) {
        return click -> {
            if (authorize(requirement)) action.accept(click);
        };
    }

    public void promptFromMenu(Click click, StaffRequirement requirement, String prompt,
                               BiConsumer<AuthorizedStaff, String> onInput, Runnable onCancel) {
        if (!authorize(requirement)) return;
        click.clickedMenu().close();
        awaitInput(requirement, prompt, onInput, onCancel);
    }

    public void prompt(StaffRequirement requirement, String prompt, BiConsumer<AuthorizedStaff, String> onInput,
                       Runnable onCancel) {
        if (authorize(requirement)) awaitInput(requirement, prompt, onInput, onCancel);
    }

    private void awaitInput(StaffRequirement requirement, String prompt, BiConsumer<AuthorizedStaff, String> onInput,
                            Runnable onCancel) {
        PluginServices.chatInput().requestInput(uuid, prompt,
                input -> authorizeAs(requirement).ifPresent(issuer -> onInput.accept(issuer, input)), onCancel);
    }
}
