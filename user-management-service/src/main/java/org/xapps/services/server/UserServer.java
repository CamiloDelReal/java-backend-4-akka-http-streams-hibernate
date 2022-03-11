package org.xapps.services.server;

import akka.NotUsed;
import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.Behaviors;
import akka.http.javadsl.Http;
import akka.http.javadsl.ServerBinding;
import lombok.extern.slf4j.Slf4j;
import org.xapps.services.services.UserService;

import java.util.concurrent.CompletionStage;

@Slf4j
public class UserServer {
    public static Behavior<NotUsed> create() {
        return Behaviors.setup(context -> {
            ActorRef<UserService.Command> userServiceActor = context.spawn(UserService.create(), "UserServiceActor");
            UserRoutes userRoutes = new UserRoutes(userServiceActor, context.getSystem().scheduler());
            CompletionStage<ServerBinding> serverBinding = Http.get(context.getSystem())
                    .newServerAt("0.0.0.0", 8080)
                    .bind(userRoutes.create());
            serverBinding.whenComplete((binding, throwable) -> {
                if (throwable == null) {
                    log.debug("Server started at " + binding.localAddress());
                    log.debug("Seeding database");
                    userServiceActor.tell(new UserService.SeedCommand());
                } else {
                    log.error("Exception received", throwable);
                    context.getSystem().terminate();
                }
            });
            return Behaviors.empty();
        });
    }
}
