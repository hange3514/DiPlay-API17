package local.headunit.diplay;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import java.io.File;
import java.util.List;

/** Read-only manifest and package-manager evidence for the factory navigation link. */
final class ClusterLinkInventory {
    private static final String NAVIGATION_LINK = "com.byd.navigationlink";
    private static final String AMAP_BROADCAST = "AUTONAVI_STANDARD_BROADCAST_SEND";

    static final class Result {
        final String summary;
        final String display;

        Result(String summary, String display) {
            this.summary = summary;
            this.display = display;
        }
    }

    private ClusterLinkInventory() {}

    static Result inspect(Context context) {
        PackageManager packages = context.getPackageManager();
        String caller = context.getPackageName();
        try {
            PackageInfo app = packages.getPackageInfo(NAVIGATION_LINK,
                PackageManager.GET_RECEIVERS | PackageManager.GET_SERVICES | PackageManager.GET_PROVIDERS);
            if (app == null || app.applicationInfo == null) return unavailable();
            ApplicationInfo info = app.applicationInfo;
            boolean enabled = effectiveEnabled(packages.getApplicationEnabledSetting(NAVIGATION_LINK), info.enabled);
            boolean system = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            boolean apkReadable = apkReadable(info.sourceDir);
            int exportedReceivers = 0;
            int accessibleReceivers = 0;
            int exportedServices = 0;
            int accessibleServices = 0;
            int exportedProviders = 0;
            int readableProviders = 0;
            int writableProviders = 0;
            if (enabled && app.receivers != null) for (ActivityInfo receiver : app.receivers) {
                if (!exported(packages, receiver)) continue;
                exportedReceivers++;
                if (hasPermission(packages, caller, receiver.permission)) accessibleReceivers++;
            }
            if (enabled && app.services != null) for (ServiceInfo service : app.services) {
                if (!exported(packages, service)) continue;
                exportedServices++;
                if (hasPermission(packages, caller, service.permission)) accessibleServices++;
            }
            if (enabled && app.providers != null) for (ProviderInfo provider : app.providers) {
                if (!exported(packages, provider)) continue;
                exportedProviders++;
                if (hasPermission(packages, caller, provider.readPermission)) readableProviders++;
                if (hasPermission(packages, caller, provider.writePermission)) writableProviders++;
            }
            int amapReceivers = 0;
            int accessibleAmapReceivers = 0;
            if (enabled) {
                Intent action = new Intent(AMAP_BROADCAST).setPackage(NAVIGATION_LINK);
                List<ResolveInfo> matches = packages.queryBroadcastReceivers(action, 0);
                if (matches != null) for (ResolveInfo match : matches) {
                    ActivityInfo receiver = match == null ? null : match.activityInfo;
                    if (!exported(packages, receiver) || !NAVIGATION_LINK.equals(receiver.packageName)) continue;
                    amapReceivers++;
                    if (hasPermission(packages, caller, receiver.permission)) accessibleAmapReceivers++;
                }
            }
            return new Result("clusterLink=present clusterLinkVersion=" + app.versionCode +
                " clusterLinkEnabled=" + enabled + " clusterLinkSystem=" + system +
                " clusterLinkApkReadable=" + apkReadable + " clusterExportedRx=" + exportedReceivers +
                " clusterPermittedRx=" + accessibleReceivers + " clusterExportedServices=" + exportedServices +
                " clusterPermittedServices=" + accessibleServices + " clusterExportedProviders=" + exportedProviders +
                " clusterReadableProviders=" + readableProviders + " clusterWritableProviders=" + writableProviders +
                " clusterAmapRx=" + amapReceivers + " clusterPermittedAmapRx=" + accessibleAmapReceivers,
                "原厂 NavigationLink 已安装（" + app.versionCode + "），" + (enabled ? "已启用" : "已停用") +
                "，APK " + (apkReadable ? "可读" : "不可读") + "。\n" +
                "清单中高德广播匹配 " + amapReceivers + " 个，当前应用满足清单权限 " + accessibleAmapReceivers + " 个。\n" +
                "已导出广播接收器 / 服务 / 数据提供者：" + exportedReceivers + " / " + exportedServices + " / " + exportedProviders +
                "。清单只能提供静态接口线索，仪表通路仍需实车观察。");
        } catch (PackageManager.NameNotFoundException absent) {
            return new Result("clusterLink=absent", "未发现原厂 NavigationLink。");
        } catch (RuntimeException failure) {
            return unavailable();
        }
    }

    private static Result unavailable() {
        return new Result("clusterLink=unavailable", "无法读取原厂 NavigationLink 的应用信息。");
    }

    private static boolean exported(PackageManager packages, ComponentInfo component) {
        return component != null && component.exported && effectiveEnabled(
            packages.getComponentEnabledSetting(new ComponentName(component.packageName, component.name)),
            component.enabled);
    }

    private static boolean effectiveEnabled(int state, boolean manifestEnabled) {
        if (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) return manifestEnabled;
        return state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    private static boolean hasPermission(PackageManager packages, String caller, String permission) {
        return permission == null || permission.isEmpty() ||
            packages.checkPermission(permission, caller) == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean apkReadable(String path) {
        if (path == null || !path.endsWith(".apk")) return false;
        File file = new File(path);
        return file.isFile() && file.canRead();
    }
}
