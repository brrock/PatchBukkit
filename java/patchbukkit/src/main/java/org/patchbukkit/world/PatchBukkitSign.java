package org.patchbukkit.world;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.patchbukkit.bridge.BridgeUtils;
import patchbukkit.bridge.NativeBridgeFfi;
import patchbukkit.world.SignLinesRequest;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Sign block state backed by Pumpkin's sign block entity. Line edits are kept in the
 * snapshot until {@code update()}, as in Bukkit. Methods this class does not handle
 * fall through to the plain {@link PatchBukkitBlockState}.
 */
public final class PatchBukkitSign implements InvocationHandler {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final PatchBukkitBlockState base;
    private final String[] lines = {"", "", "", ""};
    private boolean waxed;

    private PatchBukkitSign(Block block) {
        this.base = new PatchBukkitBlockState(block);
        try {
            var resp = NativeBridgeFfi.getSignLines(request(block).build());
            if (resp != null) {
                for (int i = 0; i < 4 && i < resp.getLinesCount(); i++) {
                    lines[i] = toLegacy(resp.getLines(i));
                }
            }
        } catch (Throwable t) { BridgeUtils.logBridgeFailure("getSignLines", t); }
    }

    public static Sign create(Block block) {
        return (Sign) Proxy.newProxyInstance(PatchBukkitSign.class.getClassLoader(),
            new Class<?>[] {Sign.class}, new PatchBukkitSign(block));
    }

    public static boolean isSign(org.bukkit.Material type) {
        String name = type.name();
        return name.endsWith("_SIGN");
    }

    private static SignLinesRequest.Builder request(Block block) {
        return SignLinesRequest.newBuilder()
            .setWorldUuid(BridgeUtils.convertUuid(block.getWorld().getUID()))
            .setX(block.getX()).setY(block.getY()).setZ(block.getZ());
    }

    /** Pumpkin may hold a line as plain text or as a JSON text component. */
    private static String toLegacy(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("\"")) {
            try {
                Component c = net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().deserialize(trimmed);
                return LEGACY.serialize(c);
            } catch (RuntimeException ignored) {}
        }
        return raw;
    }

    private boolean push(boolean force) {
        try {
            var req = request(base.getBlock());
            for (String line : lines) req.addLines(line == null ? "" : line);
            var resp = NativeBridgeFfi.setSignLines(req.build());
            return resp != null && resp.getFound();
        } catch (Throwable t) {
            BridgeUtils.logBridgeFailure("setSignLines", t);
            return false;
        }
    }

    private SignSide side() {
        return (SignSide) Proxy.newProxyInstance(PatchBukkitSign.class.getClassLoader(),
            new Class<?>[] {SignSide.class}, (proxy, method, args) -> sideCall(method, args));
    }

    private Object sideCall(Method method, Object[] args) throws Throwable {
        switch (method.getName()) {
            case "getLine": return lines[(int) args[0]];
            case "setLine": lines[(int) args[0]] = args[1] == null ? "" : (String) args[1]; return null;
            case "getLines": return lines.clone();
            case "line":
                if (args.length == 1) return LEGACY.deserialize(lines[(int) args[0]]);
                lines[(int) args[0]] = args[1] == null ? "" : LEGACY.serialize((Component) args[1]);
                return null;
            case "lines": {
                List<Component> out = new ArrayList<>();
                for (String l : lines) out.add(LEGACY.deserialize(l));
                return out;
            }
            case "isGlowingText": return false;
            case "setGlowingText": return null;
            case "getColor": return org.bukkit.DyeColor.BLACK;
            case "setColor": return null;
            case "hashCode": return System.identityHashCode(this);
            case "equals": return args[0] == this;
            case "toString": return "PatchBukkitSignSide";
            default: return defaultValue(method.getReturnType());
        }
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        switch (method.getName()) {
            case "getLine", "setLine", "getLines", "line", "lines", "isGlowingText", "setGlowingText", "getColor", "setColor":
                if (method.getDeclaringClass() == Object.class) break;
                return sideCall(method, args);
            case "getSide": return side();
            case "getTargetSide": return side();
            case "getInteractableSideFor": return Side.FRONT;
            case "isWaxed": case "isEditable":
                return method.getName().equals("isWaxed") == waxed;
            case "setWaxed": waxed = (boolean) args[0]; return null;
            case "setEditable": waxed = !(boolean) args[0]; return null;
            case "update":
                return push(args != null && args.length > 0 && (boolean) args[0]);
            case "getAllowedEditorUniqueId": return null;
            case "hashCode": return System.identityHashCode(proxy);
            case "equals": return args[0] == proxy;
            case "toString": return "PatchBukkitSign" + java.util.Arrays.toString(lines);
            default: break;
        }
        try {
            Method target = base.getClass().getMethod(method.getName(), method.getParameterTypes());
            return target.invoke(base, args);
        } catch (NoSuchMethodException e) {
            return defaultValue(method.getReturnType());
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == void.class) return null;
        if (type == char.class) return '\0';
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        return 0;
    }
}
