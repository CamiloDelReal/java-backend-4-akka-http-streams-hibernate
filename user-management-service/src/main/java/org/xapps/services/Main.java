package org.xapps.services;

import akka.actor.typed.ActorSystem;
import org.xapps.services.server.UserServer;

public class Main {
    public static void main(String[] args) {
        ActorSystem.create(UserServer.create(), "UserManagementService");
    }
}
