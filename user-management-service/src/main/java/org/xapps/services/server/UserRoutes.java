package org.xapps.services.server;

import akka.NotUsed;
import akka.actor.typed.ActorRef;
import akka.actor.typed.Scheduler;
import akka.http.javadsl.marshallers.jackson.Jackson;
import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpEntities;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.PathMatchers;
import akka.http.javadsl.server.Route;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Source;
import akka.stream.typed.javadsl.ActorFlow;
import akka.util.ByteString;
import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.xapps.services.entities.User;
import org.xapps.services.services.UserService;
import org.xapps.services.services.requests.Login;
import org.xapps.services.services.responses.LoginResponse;
import org.xapps.services.services.responses.Response;
import org.xapps.services.services.responses.UserResponse;
import org.xapps.services.services.responses.UsersResponse;
import org.xapps.services.services.utils.PropertiesProvider;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import static akka.http.javadsl.server.Directives.*;

@Slf4j
public class UserRoutes {
    private final ActorRef<UserService.Command> userServiceActor;
    private final Scheduler scheduler;
    private final ObjectMapper objectMapper;
    private final PropertiesProvider properties;

    public UserRoutes(ActorRef<UserService.Command> userServiceActor, Scheduler scheduler) {
        this.userServiceActor = userServiceActor;
        this.scheduler = scheduler;
        this.objectMapper = new ObjectMapper();
        this.properties = PropertiesProvider.getInstance();
    }

    private Route authenticateWithJwt(Function<Optional<User>, Route> inner) {
        return optionalHeaderValueByName("Authorization", authorizationHeader -> {
            if (authorizationHeader.isPresent() && authorizationHeader.get().startsWith("Bearer")) {
                String token = authorizationHeader.get().substring(7);
                try {
                    Algorithm algorithm = Algorithm.HMAC256(properties.securityTokenKey());
                    JWTVerifier verifier = JWT.require(algorithm)
                            .withIssuer("XApps")
                            .build();
                    DecodedJWT jwt = verifier.verify(token);
                    User principal = objectMapper.readValue(jwt.getSubject(), User.class);
                    return inner.apply(Optional.of(principal));
                } catch (JsonProcessingException ex) {
                    log.error("Exception captured", ex);
                    return complete(StatusCodes.UNAUTHORIZED);
                }
            } else {
                return inner.apply(Optional.empty());
            }
        });
    }

    private Route loginUser(Login login) {
        Flow<Login, LoginResponse, NotUsed> loginFlow = ActorFlow.ask(userServiceActor, Duration.ofSeconds(5), (loginObj, me) -> {
            return new UserService.LoginCommand(loginObj, me);
        });
        Flow<LoginResponse, ByteString, NotUsed> marshallerFlow = Flow.of(LoginResponse.class)
                .map(response -> ByteString.fromString(objectMapper.writeValueAsString(response)));
        Source<ByteString, NotUsed> loginSource = Source.single(login)
                .via(loginFlow)
                .via(marshallerFlow);
        return complete(HttpEntities.create(ContentTypes.APPLICATION_JSON, loginSource));
    }

    private Route createUser(User user) {
        Flow<User, UserResponse, NotUsed> createFlow = ActorFlow.ask(userServiceActor, Duration.ofSeconds(5), (userObj, me) -> {
            return new UserService.CreateCommand(userObj, me);
        });
        Flow<UserResponse, ByteString, NotUsed> marshallerFlow = Flow.of(UserResponse.class)
                .map(r -> ByteString.fromString(objectMapper.writeValueAsString(r)));
        Source<ByteString, NotUsed> createSource = Source.single(user)
                .via(createFlow)
                .via(marshallerFlow);
        return complete(HttpEntities.create(ContentTypes.APPLICATION_JSON, createSource));
    }

    private Route getAllUsers() {
        Flow<Integer, UsersResponse, NotUsed> usersFlow = ActorFlow.ask(userServiceActor, Duration.ofSeconds(5), (discardable, me) -> {
            return new UserService.ReadAllCommand(me);
        });
        Flow<UsersResponse, ByteString, NotUsed> marshallerFlow = Flow.of(UsersResponse.class)
                .map(r -> ByteString.fromString(objectMapper.writeValueAsString(r)));
        Source<ByteString, NotUsed> usersSource = Source.single(1)
                .via(usersFlow)
                .via(marshallerFlow);
        return complete(HttpEntities.create(ContentTypes.APPLICATION_JSON, usersSource));
    }

    private Route getUser(Long id) {
        Flow<Long, UserResponse, NotUsed> userFlow = ActorFlow.ask(userServiceActor, Duration.ofSeconds(5), (userId, me) -> {
            return new UserService.ReadCommand(userId, me);
        });
        Flow<UserResponse, ByteString, NotUsed> marshallerFlow = Flow.of(UserResponse.class)
                .map(r -> ByteString.fromString(objectMapper.writeValueAsString(r)));
        Source<ByteString, NotUsed> userSource = Source.single(id)
                .via(userFlow)
                .via(marshallerFlow);
        return complete(HttpEntities.create(ContentTypes.APPLICATION_JSON, userSource));
    }

    private Route updateUser(Long id, User user) {
        Flow<User, UserResponse, NotUsed> updateFlow = ActorFlow.ask(userServiceActor, Duration.ofSeconds(5), (userObj, me) -> {
            return new UserService.UpdateCommand(userObj.getId(), userObj, me);
        });
        Flow<UserResponse, ByteString, NotUsed> marshallerFlow = Flow.of(UserResponse.class)
                .map(r -> ByteString.fromString(objectMapper.writeValueAsString(r)));
        user.setId(id); // Workaround to not create an object containing both data
        Source<ByteString, NotUsed> updateSource = Source.single(user)
                .via(updateFlow)
                .via(marshallerFlow);
        return complete(HttpEntities.create(ContentTypes.APPLICATION_JSON, updateSource));
    }

    private Route deleteUser(Long id) {
        Flow<Long, Response, NotUsed> deleteFlow = ActorFlow.ask(userServiceActor, Duration.ofSeconds(5), (userId, me) ->
                new UserService.DeleteCommand(userId, me)
        );
        Flow<Response, ByteString, NotUsed> marshallerFlow = Flow.of(Response.class)
                .map(r -> ByteString.fromString(objectMapper.writeValueAsString(r)));
        Source<ByteString, NotUsed> deleteSource = Source.single(id)
                .via(deleteFlow)
                .via(marshallerFlow);
        return complete(HttpEntities.create(ContentTypes.APPLICATION_JSON, deleteSource));
    }

    public Route create() {
        return pathPrefix("users", () ->
                concat(
                        path("login", () -> post(() -> entity(Jackson.unmarshaller(Login.class), login -> loginUser(login)))),
                        pathEnd(() -> concat(
                                get(() -> authenticateWithJwt(principal ->
                                        authorize(() -> {
                                            return principal.isPresent() && principal.get().isAdministrator();
                                        }, () -> {
                                            return getAllUsers();
                                        }))),
                                post(() -> authenticateWithJwt(principal -> entity(Jackson.unmarshaller(User.class), user ->
                                        authorize(() -> {
                                            return !user.isAdministrator() || (principal.isPresent() && principal.get().isAdministrator());
                                        }, () -> {
                                            return createUser(user);
                                        }))))
                        )),
                        path(PathMatchers.longSegment(), id -> concat(
                                get(() -> authenticateWithJwt(principal ->
                                        authorize(() -> {
                                            return principal.isPresent() && (principal.get().isAdministrator() || Objects.equals(principal.get().getId(), id));
                                        }, () -> {
                                            return getUser(id);
                                        }))),
                                put(() -> entity(Jackson.unmarshaller(User.class), user ->
                                        authenticateWithJwt(principal ->
                                                authorize(() -> {
                                                    return principal.isPresent() && (principal.get().isAdministrator() || Objects.equals(principal.get().getId(), id));
                                                }, () -> {
                                                    return updateUser(id, user);
                                                })))),
                                delete(() -> authenticateWithJwt(principal ->
                                        authorize(() -> {
                                            return principal.isPresent() && (principal.get().isAdministrator() || Objects.equals(principal.get().getId(), id));
                                        }, () -> {
                                            return deleteUser(id);
                                        })))
                        ))
                )
        );
    }
}
