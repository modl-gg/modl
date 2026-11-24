package gg.modl.minecraft.core;

import gg.modl.minecraft.core.staff.action.AuthorizedStaff;
import gg.modl.minecraft.core.staff.action.StaffActionDenied;
import gg.modl.minecraft.core.staff.action.StaffActionGate;
import gg.modl.minecraft.core.staff.action.StaffActors;
import gg.modl.minecraft.core.staff.action.StaffRequirement;
import lombok.RequiredArgsConstructor;
import revxrsal.commands.Lamp;
import revxrsal.commands.annotation.list.AnnotationList;
import revxrsal.commands.command.CommandActor;
import revxrsal.commands.exception.SendableException;

@RequiredArgsConstructor
public final class CommandAccessPolicy {
    private final StaffActionGate gate;

    public void requireRunning() {
        try {
            gate.requireRunning();
        } catch (StaffActionDenied denied) {
            throw deny(denied.getMessage());
        }
    }

    public <A extends CommandActor> void install(Lamp.Builder<A> builder) {
        builder.commandCondition(context -> enforce(context.command().annotations(), context.actor()));
        builder.parameterTypes(types -> types.addContextParameter(AuthorizedStaff.class,
                (parameter, context) -> authorize(context.command().annotations(), context.actor())));
    }

    public void enforce(AnnotationList annotations, CommandActor actor) {
        authorize(annotations, actor);
    }

    AuthorizedStaff authorize(AnnotationList annotations, CommandActor actor) {
        try {
            return gate.require(StaffActors.of(actor), StaffRequirement.fromAnnotations(annotations));
        } catch (StaffActionDenied denied) {
            throw deny(denied.getMessage());
        }
    }

    static SendableException deny(String message) {
        return new SendableException(message) {
            @Override
            public void sendTo(CommandActor actor) {
                actor.reply(message);
            }
        };
    }
}
