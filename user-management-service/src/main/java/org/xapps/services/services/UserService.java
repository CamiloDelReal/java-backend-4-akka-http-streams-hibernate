package org.xapps.services.services;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import at.favre.lib.crypto.bcrypt.BCrypt;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.xapps.services.entities.Role;
import org.xapps.services.entities.User;
import org.xapps.services.repositories.RoleRepository;
import org.xapps.services.repositories.UserRepository;
import org.xapps.services.services.requests.Login;
import org.xapps.services.services.responses.*;
import org.xapps.services.services.utils.PropertiesProvider;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
public class UserService extends AbstractBehavior<UserService.Command> {
    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PropertiesProvider properties;

    public interface Command extends Serializable {
    }

    public record SeedCommand() implements Command {
        @Serial
        private static final long serialVersionUID = 324723847875L;
    }

    public record LoginCommand(
            Login login,
            ActorRef<LoginResponse> replyTo
    ) implements Command {
        @Serial
        private static final long serialVersionUID = 7498234832749L;
    }

    public record CreateCommand(
            User user,
            ActorRef<UserResponse> replyTo
    ) implements Command {
        @Serial
        private static final long serialVersionUID = 384358284893423L;
    }

    public record ReadAllCommand(
            ActorRef<UsersResponse> replyTo
    ) implements Command {
        @Serial
        private static final long serialVersionUID = 1283238954984L;
    }

    public record ReadCommand(
            Long id,
            ActorRef<UserResponse> replyTo
    ) implements Command {
        @Serial
        private static final long serialVersionUID = 123548543985485L;
    }

    public record UpdateCommand(
            Long id,
            User user,
            ActorRef<UserResponse> replyTo
    ) implements Command {
        @Serial
        private static final long serialVersionUID = 545238243897L;
    }

    public record DeleteCommand(
            Long id,
            ActorRef<Response> replyTo
    ) implements Command {
        @Serial
        private static final long serialVersionUID = 89743274374875L;
    }

    private UserService(ActorContext<Command> context) {
        super(context);
        this.objectMapper = new ObjectMapper();
        this.userRepository = new UserRepository();
        this.roleRepository = new RoleRepository();
        this.properties = PropertiesProvider.getInstance();
    }

    public static Behavior<Command> create() {
        return Behaviors.setup(UserService::new);
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(SeedCommand.class, this::seed)
                .onMessage(LoginCommand.class, this::login)
                .onMessage(CreateCommand.class, this::create)
                .onMessage(ReadAllCommand.class, this::readAll)
                .onMessage(ReadCommand.class, this::read)
                .onMessage(UpdateCommand.class, this::update)
                .onMessage(DeleteCommand.class, this::delete)
                .build();
    }

    private Behavior<Command> seed(SeedCommand command) {
        Role administratorRole = null;
        if (roleRepository.count() == 0) {
            administratorRole = new Role(Role.ADMINISTRATOR);
            administratorRole = roleRepository.create(administratorRole);
            Role guestRole = new Role(Role.GUEST);
            guestRole = roleRepository.create(guestRole);
        }
        if (userRepository.count() == 0) {
            if (administratorRole == null) {
                administratorRole = roleRepository.getByName(Role.ADMINISTRATOR);
            }
            User administrator = new User(
                    properties.defaultRootEmail(),
                    BCrypt.withDefaults().hashToString(properties.defaultHashRound(), properties.defaultRootPassword().toCharArray()),
                    properties.defaultRootFirstName(),
                    properties.defaultRootLastName(),
                    List.of(administratorRole)
            );
            userRepository.create(administrator);
        }
        return Behaviors.same();
    }

    private Behavior<Command> login(LoginCommand command) {
        User user = userRepository.getByEmail(command.login.email());
        if (user != null) {
            BCrypt.Result result = BCrypt.verifyer().verify(command.login.password().toCharArray(), user.getPassword());
            if (result.verified) {
                try {
                    Algorithm algorithm = Algorithm.HMAC256(properties.securityTokenKey());
                    long currentTimestamp = Instant.now().toEpochMilli();
                    long expirationTimestamp = currentTimestamp + properties.securityValidity();
                    String token = JWT.create()
                            .withIssuer("XApps")
                            .withSubject(objectMapper.writeValueAsString(user))
                            .withIssuedAt(new Date(currentTimestamp))
                            .withExpiresAt(new Date(expirationTimestamp))
                            .sign(algorithm);
                    command.replyTo.tell(new LoginResponse(ResponseType.OK, new Authentication(token, expirationTimestamp)));
                } catch (JsonProcessingException e) {
                    log.error("Exception captured");
                    command.replyTo.tell(new LoginResponse(ResponseType.UNAUTHORIZED));
                }
            } else {
                command.replyTo.tell(new LoginResponse(ResponseType.UNAUTHORIZED));
            }
        } else {
            command.replyTo.tell(new LoginResponse(ResponseType.UNAUTHORIZED));
        }
        return Behaviors.same();
    }

    private Behavior<Command> create(CreateCommand command) {
        User emailDuplicity = userRepository.getByEmail(command.user.getEmail());
        if (emailDuplicity == null) {
            User newUser = command.user;
            newUser.setPassword(BCrypt.withDefaults().hashToString(12, newUser.getPassword().toCharArray()));
            List<Role> roles = null;
            if (newUser.getRoles() != null && !newUser.getRoles().isEmpty()) {
                roles = roleRepository.getByNames(newUser.getRoles().stream().map(Role::getName).collect(Collectors.toList()));
            }
            if(roles == null || roles.isEmpty()) {
                Role guestRole = roleRepository.getByName(Role.GUEST);
                roles = List.of(guestRole);
            }
            newUser.setRoles(roles);
            userRepository.create(newUser);
            command.replyTo.tell(new UserResponse(ResponseType.OK, newUser));
        } else {
            command.replyTo.tell(new UserResponse(ResponseType.EMAIL_NOT_AVAILABLE));
        }
        return Behaviors.same();
    }

    private Behavior<Command> readAll(ReadAllCommand command) {
        List<User> users = userRepository.readAll();
        command.replyTo.tell(new UsersResponse(ResponseType.OK, users));
        return Behaviors.same();
    }

    private Behavior<Command> read(ReadCommand command) {
        User user = userRepository.read(command.id);
        if (user != null) {
            command.replyTo.tell(new UserResponse(ResponseType.OK, user));
        } else {
            command.replyTo.tell(new UserResponse(ResponseType.NOT_FOUND));
        }
        return Behaviors.same();
    }

    private Behavior<Command> update(UpdateCommand command) {
        User user = userRepository.read(command.id);
        if (user != null) {
            User duplicity = userRepository.getByNotIdAndEmail(command.id, command.user.getEmail());
            if (duplicity == null) {
                User newUserData = command.user;
                if(command.user.getEmail() != null)
                    user.setEmail(command.user.getEmail());
                if (command.user.getPassword() != null)
                    user.setPassword(BCrypt.withDefaults().hashToString(12, newUserData.getPassword().toCharArray()));
                user.setFirstName(command.user.getFirstName());
                user.setLastName(command.user.getLastName());
                if (command.user.getRoles() != null && !command.user.getRoles().isEmpty()) {
                    List<Role> roles = roleRepository.getByNames(command.user.getRoles().stream().map(Role::getName).collect(Collectors.toList()));
                    user.setRoles(roles);
                }
                userRepository.update(user);
                command.replyTo.tell(new UserResponse(ResponseType.OK, user));
            } else {
                command.replyTo.tell(new UserResponse(ResponseType.EMAIL_NOT_AVAILABLE));
            }
        } else {
            command.replyTo.tell(new UserResponse(ResponseType.NOT_FOUND));
        }
        return Behaviors.same();
    }

    private Behavior<Command> delete(DeleteCommand command) {
        User user = userRepository.read(command.id);
        if (user != null) {
            userRepository.delete(user);
            command.replyTo.tell(new Response(ResponseType.OK));
        } else {
            command.replyTo.tell(new Response(ResponseType.NOT_FOUND));
        }
        return Behaviors.same();
    }
}
