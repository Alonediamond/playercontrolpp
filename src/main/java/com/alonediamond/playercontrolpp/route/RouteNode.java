package com.alonediamond.playercontrolpp.route;

import com.google.gson.JsonObject;

public class RouteNode {
    public double x, y, z;

    public RouteNode() {
        this(0, 0, 0);
    }

    public RouteNode(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("x", x);
        obj.addProperty("y", y);
        obj.addProperty("z", z);
        return obj;
    }

    public static RouteNode fromJson(JsonObject obj) {
        return new RouteNode(
                obj.has("x") ? obj.get("x").getAsDouble() : 0,
                obj.has("y") ? obj.get("y").getAsDouble() : 0,
                obj.has("z") ? obj.get("z").getAsDouble() : 0);
    }
}
