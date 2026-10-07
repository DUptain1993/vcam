package com.yaahua.vcam;

import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.InputConfiguration;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.view.Surface;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class Camera2SessionHook {

    private static final Set<String> HOOKED_STATE_CALLBACKS =
            Collections.synchronizedSet(new HashSet<String>());
    private static final Set<String> HOOKED_DEVICE_CLASSES =
            Collections.synchronizedSet(new HashSet<String>());

    /**
     * Detached texture. {@code new SurfaceTexture(texName)} binds a real GL name,
     * and Chrome always has a GL context on the camera thread, so a fake name
     * aborts the browser process.
     */
    static SurfaceTexture newDummySurfaceTexture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new SurfaceTexture(false);
        }
        return new SurfaceTexture(0);
    }

    private static boolean hasVirtualSurface() {
        Surface surface = SharedState.c2_virtual_surface;
        return surface != null && surface.isValid();
    }

    // ======================== processCamera2Init ========================
    public static void processCamera2Init(final Class hooked_class) {
        if (hooked_class == null) return;
        if (!HOOKED_STATE_CALLBACKS.add(hooked_class.getName())) return;
        createVirtualSurface();
        try {
        XposedHelpers.findAndHookMethod(hooked_class, "onOpened", CameraDevice.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                createVirtualSurface();

                // 释放旧的播放器资源。stop() 在未 prepare 时会抛异常，不能让它逃出 Chrome 的相机回调。
                if (SharedState.c2_player != null) {
                    try {
                        SharedState.c2_player.stop();
                        SharedState.c2_player.reset();
                        SharedState.c2_player.release();
                    } catch (Throwable t) {
                        XposedBridge.log("【VCAM】释放 c2_player: " + t);
                    }
                    SharedState.c2_player = null;
                }
                if (SharedState.c2_hw_decode_obj_1 != null) {
                    SharedState.c2_hw_decode_obj_1.stopDecode();
                    SharedState.c2_hw_decode_obj_1 = null;
                }
                if (SharedState.c2_hw_decode_obj != null) {
                    SharedState.c2_hw_decode_obj.stopDecode();
                    SharedState.c2_hw_decode_obj = null;
                }
                if (SharedState.c2_player_1 != null) {
                    try {
                        SharedState.c2_player_1.stop();
                        SharedState.c2_player_1.reset();
                        SharedState.c2_player_1.release();
                    } catch (Throwable t) {
                        XposedBridge.log("【VCAM】释放 c2_player_1: " + t);
                    }
                    SharedState.c2_player_1 = null;
                }
                SharedState.c2_preview_Surfcae_1 = null;
                SharedState.c2_reader_Surfcae_1 = null;
                SharedState.c2_reader_Surfcae = null;
                SharedState.c2_preview_Surfcae = null;
                SharedState.is_first_hook_build = true;
                SharedState.currentVideoPath = null; // 修复：摄像头重开时清空路径，确保 build() 时重建解码器
                XposedBridge.log("【VCAM】打开相机C2");

                File file;
                try {
                    file = HookGuards.getVideoFile();
                    if (file == null || !file.exists()) file = null;
                } catch (Throwable t) {
                    XposedBridge.log("【VCAM】onOpened 读取视频失败: " + t);
                    return;
                }
                SharedState.need_to_show_toast = HookGuards.shouldShowToast();
                if (file == null) {
                    if (SharedState.toast_content != null && SharedState.need_to_show_toast) {
                        try {
                            Toast.makeText(SharedState.toast_content,
                                    "No replacement video\n" + SharedState.toast_content.getPackageName() +
                                    "\nCurrent path: " + SharedState.video_path, Toast.LENGTH_SHORT).show();
                        } catch (Exception ee) {
                            XposedBridge.log("【VCAM】[toast]" + ee.toString());
                        }
                    }
                    return;
                }

                if (param.args[0] != null) {
                    try {
                        hookDeviceClass(param.args[0].getClass());
                    } catch (Throwable t) {
                        XposedBridge.log("【VCAM】hook CameraDevice 失败: " + t);
                    }
                }
            }
        });
        } catch (Throwable t) {
            HOOKED_STATE_CALLBACKS.remove(hooked_class.getName());
            XposedBridge.log("【VCAM】processCamera2Init 失败: " + t);
            return;
        }

        tryHook(hooked_class, "onError", new Class[]{CameraDevice.class, int.class}, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                XposedBridge.log("【VCAM】相机错误onerror：" + (int) param.args[1]);
            }
        });

        tryHook(hooked_class, "onDisconnected", new Class[]{CameraDevice.class}, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                XposedBridge.log("【VCAM】相机断开onDisconnected ：");
            }
        });
    }

    // ======================== hookDeviceClass ========================
    private static void hookDeviceClass(final Class deviceClass) {
        if (deviceClass == null || !HOOKED_DEVICE_CLASSES.add(deviceClass.getName())) return;

        tryHook(deviceClass, "createCaptureSession",
                new Class[]{List.class, CameraCaptureSession.StateCallback.class, Handler.class},
                new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam paramd) {
                if (paramd.args[0] == null || !hasVirtualSurface()) return;
                XposedBridge.log("【VCAM】createCaptureSession创捷捕获，原始:" + paramd.args[0].toString() +
                        "虚拟：" + SharedState.c2_virtual_surface);
                paramd.args[0] = Arrays.asList(SharedState.c2_virtual_surface);
                if (paramd.args[1] != null) {
                    processCamera2SessionCallback((CameraCaptureSession.StateCallback) paramd.args[1]);
                }
            }
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            tryHook(deviceClass, "createCaptureSessionByOutputConfigurations",
                    new Class[]{List.class, CameraCaptureSession.StateCallback.class, Handler.class},
                    new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args[0] == null || !hasVirtualSurface()) return;
                    SharedState.outputConfiguration = new OutputConfiguration(SharedState.c2_virtual_surface);
                    param.args[0] = Arrays.asList(SharedState.outputConfiguration);
                    XposedBridge.log("【VCAM】执行了createCaptureSessionByOutputConfigurations-144777");
                    if (param.args[1] != null) {
                        processCamera2SessionCallback((CameraCaptureSession.StateCallback) param.args[1]);
                    }
                }
            });
        }

        tryHook(deviceClass, "createConstrainedHighSpeedCaptureSession",
                new Class[]{List.class, CameraCaptureSession.StateCallback.class, Handler.class},
                new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args[0] == null || !hasVirtualSurface()) return;
                param.args[0] = Arrays.asList(SharedState.c2_virtual_surface);
                XposedBridge.log("【VCAM】执行了 createConstrainedHighSpeedCaptureSession -5484987");
                if (param.args[1] != null) {
                    processCamera2SessionCallback((CameraCaptureSession.StateCallback) param.args[1]);
                }
            }
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            tryHook(deviceClass, "createReprocessableCaptureSession",
                    new Class[]{InputConfiguration.class, List.class,
                            CameraCaptureSession.StateCallback.class, Handler.class},
                    new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args[1] == null || !hasVirtualSurface()) return;
                    param.args[1] = Arrays.asList(SharedState.c2_virtual_surface);
                    XposedBridge.log("【VCAM】执行了 createReprocessableCaptureSession ");
                    if (param.args[2] != null) {
                        processCamera2SessionCallback((CameraCaptureSession.StateCallback) param.args[2]);
                    }
                }
            });
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            tryHook(deviceClass, "createReprocessableCaptureSessionByConfigurations",
                    new Class[]{InputConfiguration.class, List.class,
                            CameraCaptureSession.StateCallback.class, Handler.class},
                    new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args[1] == null || !hasVirtualSurface()) return;
                    SharedState.outputConfiguration = new OutputConfiguration(SharedState.c2_virtual_surface);
                    param.args[1] = Arrays.asList(SharedState.outputConfiguration);
                    XposedBridge.log("【VCAM】执行了 createReprocessableCaptureSessionByConfigurations");
                    if (param.args[2] != null) {
                        processCamera2SessionCallback((CameraCaptureSession.StateCallback) param.args[2]);
                    }
                }
            });
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            tryHook(deviceClass, "createCaptureSession",
                    new Class[]{SessionConfiguration.class},
                    new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args[0] == null || !hasVirtualSurface()) return;
                    try {
                        XposedBridge.log("【VCAM】执行了 createCaptureSession -5484987");
                        SessionConfiguration original = (SessionConfiguration) param.args[0];
                        OutputConfiguration output = new OutputConfiguration(SharedState.c2_virtual_surface);
                        SessionConfiguration fake = new SessionConfiguration(
                                original.getSessionType(),
                                Arrays.asList(output),
                                original.getExecutor(),
                                original.getStateCallback());
                        SharedState.sessionConfiguration = original;
                        SharedState.outputConfiguration = output;
                        SharedState.fake_sessionConfiguration = fake;
                        param.args[0] = fake;
                        processCamera2SessionCallback(original.getStateCallback());
                    } catch (Throwable t) {
                        XposedBridge.log("【VCAM】替换 SessionConfiguration 失败: " + t);
                    }
                }
            });
        }
    }

    private static void tryHook(Class clazz, String method, Class[] params, XC_MethodHook hook) {
        try {
            XposedHelpers.findAndHookMethod(clazz, method, appendHook(params, hook));
        } catch (Throwable t) {
            XposedBridge.log("【VCAM】跳过 " + method + ": " + t);
        }
    }

    private static Object[] appendHook(Class[] params, XC_MethodHook hook) {
        Object[] args = new Object[params.length + 1];
        System.arraycopy(params, 0, args, 0, params.length);
        args[params.length] = hook;
        return args;
    }

    // ======================== createVirtualSurface ========================
    private static Surface createVirtualSurface() {
        if (hasVirtualSurface()) {
            return SharedState.c2_virtual_surface;
        }
        try {
            SurfaceTexture texture = newDummySurfaceTexture();
            int width = SharedState.c2_ori_width > 0 ? SharedState.c2_ori_width : 1280;
            int height = SharedState.c2_ori_height > 0 ? SharedState.c2_ori_height : 720;
            try {
                texture.setDefaultBufferSize(width, height);
            } catch (Throwable ignored) {
            }
            Surface surface = new Surface(texture);
            SharedState.c2_virtual_surfaceTexture = texture;
            SharedState.c2_virtual_surface = surface;
            SharedState.need_recreate = false;
            XposedBridge.log("【VCAM】【重建垃圾场】" + surface);
            return surface;
        } catch (Throwable t) {
            XposedBridge.log("【VCAM】创建虚拟Surface失败: " + t);
            return null;
        }
    }

    // ======================== processCamera2Play ========================
    public static void processCamera2Play() {
        File videoFile = HookGuards.getVideoFile();
        String newPath = (videoFile != null) ? videoFile.getAbsolutePath() : null;
        if (newPath == null) return;

        // === 断点续传：检查是否有保存的播放位置 ===
        String oldPath = SharedState.currentVideoPath;
        SharedState.currentVideoPath = newPath;
        long resumePos = 0;
        if (newPath.equals(oldPath)) {
            // 同一视频继续播放 → 不重置位置
            return;
        }
        // 换视频：保存旧位置，检查新视频是否有存档
        if (oldPath != null && SharedState.c2_hw_decode_obj != null) {
            long oldPos = SharedState.c2_hw_decode_obj.getCurrentPositionMs();
            if (oldPos > 0) SharedState.videoPositions.put(oldPath, oldPos);
        }
        Long saved = SharedState.videoPositions.get(newPath);
        resumePos = (saved != null) ? saved : 0;
        // ======================
        // Reader Surface 1
        if (SharedState.c2_reader_Surfcae != null) {
            if (SharedState.c2_hw_decode_obj != null) {
                SharedState.c2_hw_decode_obj.stopDecode();
                SharedState.c2_hw_decode_obj = null;
            }
            SharedState.c2_hw_decode_obj = new VideoToFrames();
            try {
                if (SharedState.imageReaderFormat == 256) {
                    SharedState.c2_hw_decode_obj.setSaveFrames("null", OutputImageFormat.JPEG);
                } else {
                    SharedState.c2_hw_decode_obj.setSaveFrames("null", OutputImageFormat.NV21);
                }
                SharedState.c2_hw_decode_obj.set_surfcae(SharedState.c2_reader_Surfcae);
                if (resumePos > 0) SharedState.c2_hw_decode_obj.seekTo(resumePos);
                if (SharedState.playPaused) SharedState.c2_hw_decode_obj.setPaused(true);
                SharedState.c2_hw_decode_obj.decode(HookGuards.getVideoFile().getAbsolutePath());
            } catch (Throwable throwable) {
                XposedBridge.log("【VCAM】" + throwable);
            }
        }

        // Reader Surface 2
        if (SharedState.c2_reader_Surfcae_1 != null) {
            if (SharedState.c2_hw_decode_obj_1 != null) {
                SharedState.c2_hw_decode_obj_1.stopDecode();
                SharedState.c2_hw_decode_obj_1 = null;
            }
            SharedState.c2_hw_decode_obj_1 = new VideoToFrames();
            try {
                if (SharedState.imageReaderFormat == 256) {
                    SharedState.c2_hw_decode_obj_1.setSaveFrames("null", OutputImageFormat.JPEG);
                } else {
                    SharedState.c2_hw_decode_obj_1.setSaveFrames("null", OutputImageFormat.NV21);
                }
                SharedState.c2_hw_decode_obj_1.set_surfcae(SharedState.c2_reader_Surfcae_1);
                if (resumePos > 0) SharedState.c2_hw_decode_obj_1.seekTo(resumePos);
                if (SharedState.playPaused) SharedState.c2_hw_decode_obj_1.setPaused(true);
                SharedState.c2_hw_decode_obj_1.decode(HookGuards.getVideoFile().getAbsolutePath());
            } catch (Throwable throwable) {
                XposedBridge.log("【VCAM】" + throwable);
            }
        }

        // Preview Surface 1
        if (SharedState.c2_preview_Surfcae != null) {
            if (SharedState.c2_player == null) {
                SharedState.c2_player = new MediaPlayer();
            } else {
                SharedState.c2_player.release();
                SharedState.c2_player = new MediaPlayer();
            }
            SharedState.c2_player.setSurface(SharedState.c2_preview_Surfcae);
            if (!HookGuards.shouldPlaySound()) {
                SharedState.c2_player.setVolume(0, 0);
            }
            SharedState.c2_player.setLooping(true);
            try {
                SharedState.c2_player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                    public void onPrepared(MediaPlayer mp) {
                        SharedState.c2_player.start();
                    }
                });
                SharedState.c2_player.setDataSource(HookGuards.getVideoFile().getAbsolutePath());
                SharedState.c2_player.prepareAsync();
            } catch (Exception e) {
                XposedBridge.log("【VCAM】[c2player][" + SharedState.c2_preview_Surfcae.toString() + "]" + e);
            }
        }

        // Preview Surface 2
        if (SharedState.c2_preview_Surfcae_1 != null) {
            if (SharedState.c2_player_1 == null) {
                SharedState.c2_player_1 = new MediaPlayer();
            } else {
                SharedState.c2_player_1.release();
                SharedState.c2_player_1 = new MediaPlayer();
            }
            SharedState.c2_player_1.setSurface(SharedState.c2_preview_Surfcae_1);
            if (!HookGuards.shouldPlaySound()) {
                SharedState.c2_player_1.setVolume(0, 0);
            }
            SharedState.c2_player_1.setLooping(true);
            try {
                SharedState.c2_player_1.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                    public void onPrepared(MediaPlayer mp) {
                        SharedState.c2_player_1.start();
                    }
                });
                SharedState.c2_player_1.setDataSource(HookGuards.getVideoFile().getAbsolutePath());
                SharedState.c2_player_1.prepareAsync();
            } catch (Exception e) {
                XposedBridge.log("【VCAM】[c2player1]" + "[ " + SharedState.c2_preview_Surfcae_1.toString() + "]" + e);
            }
        }
        XposedBridge.log("【VCAM】Camera2处理过程完全执行");
    }

    // ======================== processCamera2SessionCallback ========================
    private static final Set<String> HOOKED_SESSION_CALLBACKS =
            Collections.synchronizedSet(new HashSet<String>());

    private static void processCamera2SessionCallback(CameraCaptureSession.StateCallback callback_class) {
        if (callback_class == null) return;
        Class clazz = callback_class.getClass();
        if (!HOOKED_SESSION_CALLBACKS.add(clazz.getName())) return;
        try {
            tryHook(clazz, "onConfigureFailed", new Class[]{CameraCaptureSession.class}, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    XposedBridge.log("【VCAM】onConfigureFailed ：" + param.args[0]);
                }
            });
            tryHook(clazz, "onConfigured", new Class[]{CameraCaptureSession.class}, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    XposedBridge.log("【VCAM】onConfigured ：" + param.args[0]);
                }
            });
            tryHook(clazz, "onClosed", new Class[]{CameraCaptureSession.class}, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    XposedBridge.log("【VCAM】onClosed ：" + param.args[0]);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("【VCAM】hook session callback 失败: " + t);
        }
    }

    // ======================== 热切换：视频变更时重新加载播放器 ========================
    public static void reloadVideo() {
        HookGuards.getConfig().forceReload();
        if (HookGuards.isDisabled()) {
            stopAllPlayers();
            return;
        }
        File newFile = HookGuards.getVideoFile();
        String newPath = newFile.getAbsolutePath();
        boolean playSound = HookGuards.shouldPlaySound();

        XposedBridge.log("【VCAM】热切换视频 → " + newPath + " 声音=" + playSound);
// === 断点续传逻辑 ===
        String oldPath = SharedState.currentVideoPath;
        SharedState.currentVideoPath = newPath;
        long resumePos = 0;
        // 保存旧视频的播放位置
        if (oldPath != null && !oldPath.equals(newPath) && SharedState.c2_hw_decode_obj != null) {
            long oldPos = SharedState.c2_hw_decode_obj.getCurrentPositionMs();
            if (oldPos > 0) SharedState.videoPositions.put(oldPath, oldPos);
        }
        // 检查新视频是否有保存的播放位置
        if (newPath.equals(oldPath)) {
            // 同一视频热重载 → 断点续传
            Long saved = SharedState.videoPositions.get(newPath);
            resumePos = (saved != null) ? saved : 0;
        } else {
            Long saved = SharedState.videoPositions.get(newPath);
            resumePos = (saved != null) ? saved : 0;
        }
        // ======================
        SharedState.seekPositionMs = resumePos;
        // ======================

        // Reader Surface 1 — 若被 stopAllPlayers 置 null 则重建
        if (SharedState.c2_reader_Surfcae != null) {
            try {
                if (SharedState.c2_hw_decode_obj == null) {
                    SharedState.c2_hw_decode_obj = new VideoToFrames();
                } else {
                    SharedState.c2_hw_decode_obj.stopDecode();
                    SharedState.c2_hw_decode_obj = new VideoToFrames();
                }
                if (SharedState.imageReaderFormat == 256) {
                    SharedState.c2_hw_decode_obj.setSaveFrames("null", OutputImageFormat.JPEG);
                } else {
                    SharedState.c2_hw_decode_obj.setSaveFrames("null", OutputImageFormat.NV21);
                }
                SharedState.c2_hw_decode_obj.set_surfcae(SharedState.c2_reader_Surfcae);
                if (resumePos > 0) SharedState.c2_hw_decode_obj.seekTo(resumePos);
                if (SharedState.playPaused) SharedState.c2_hw_decode_obj.setPaused(true);
                SharedState.c2_hw_decode_obj.decode(newPath);
            } catch (Throwable t) {
                XposedBridge.log("【VCAM】热切换 reader1 失败: " + t);
            }
        }

        // Reader Surface 2
        if (SharedState.c2_reader_Surfcae_1 != null) {
            try {
                if (SharedState.c2_hw_decode_obj_1 == null) {
                    SharedState.c2_hw_decode_obj_1 = new VideoToFrames();
                } else {
                    SharedState.c2_hw_decode_obj_1.stopDecode();
                    SharedState.c2_hw_decode_obj_1 = new VideoToFrames();
                }
                if (SharedState.imageReaderFormat == 256) {
                    SharedState.c2_hw_decode_obj_1.setSaveFrames("null", OutputImageFormat.JPEG);
                } else {
                    SharedState.c2_hw_decode_obj_1.setSaveFrames("null", OutputImageFormat.NV21);
                }
                SharedState.c2_hw_decode_obj_1.set_surfcae(SharedState.c2_reader_Surfcae_1);
                if (resumePos > 0) SharedState.c2_hw_decode_obj_1.seekTo(resumePos);
                if (SharedState.playPaused) SharedState.c2_hw_decode_obj_1.setPaused(true);
                SharedState.c2_hw_decode_obj_1.decode(newPath);
            } catch (Throwable t) {
                XposedBridge.log("【VCAM】热切换 reader2 失败: " + t);
            }
        }

        // Preview Surface 1 — 若被 stopAllPlayers 置 null 则重建
        if (SharedState.c2_preview_Surfcae != null) {
            if (SharedState.c2_player == null) {
                SharedState.c2_player = new MediaPlayer();
                SharedState.c2_player.setSurface(SharedState.c2_preview_Surfcae);
            }
            try {
                SharedState.c2_player.reset();
                SharedState.c2_player.setSurface(SharedState.c2_preview_Surfcae);
                SharedState.c2_player.setVolume(playSound ? 1f : 0f, playSound ? 1f : 0f);
                SharedState.c2_player.setLooping(true);
                SharedState.c2_player.setOnPreparedListener(mp -> SharedState.c2_player.start());
                SharedState.c2_player.setDataSource(newPath);
                SharedState.c2_player.prepare();
            } catch (Exception e) {
                XposedBridge.log("【VCAM】热切换 c2_player 失败: " + e);
            }
        }

        // Preview Surface 2
        if (SharedState.c2_preview_Surfcae_1 != null) {
            if (SharedState.c2_player_1 == null) {
                SharedState.c2_player_1 = new MediaPlayer();
                SharedState.c2_player_1.setSurface(SharedState.c2_preview_Surfcae_1);
            }
            try {
                SharedState.c2_player_1.reset();
                SharedState.c2_player_1.setSurface(SharedState.c2_preview_Surfcae_1);
                SharedState.c2_player_1.setVolume(playSound ? 1f : 0f, playSound ? 1f : 0f);
                SharedState.c2_player_1.setLooping(true);
                SharedState.c2_player_1.setOnPreparedListener(mp -> SharedState.c2_player_1.start());
                SharedState.c2_player_1.setDataSource(newPath);
                SharedState.c2_player_1.prepare();
            } catch (Exception e) {
                XposedBridge.log("【VCAM】热切换 c2_player_1 失败: " + e);
            }
        }

        XposedBridge.log("【VCAM】热切换完成");
    }

    // ======================== 声音开关：动态更新音量 ========================
    public static void updateSoundVolume() {
        boolean playSound = HookGuards.shouldPlaySound();
        float vol = playSound ? 1f : 0f;
        XposedBridge.log("【VCAM】更新音量 → " + (playSound ? "开" : "关"));

        if (SharedState.c2_player != null) {
            SharedState.c2_player.setVolume(vol, vol);
        }
        if (SharedState.c2_player_1 != null) {
            SharedState.c2_player_1.setVolume(vol, vol);
        }
    }

    // ======================== 停止所有播放器（禁用模块时调用） ========================
    public static void stopAllPlayers() {
        XposedBridge.log("【VCAM】Camera2 停止所有播放器/解码器");
        try {
            if (SharedState.c2_player != null) {
                SharedState.c2_player.stop();
                SharedState.c2_player.reset();
                SharedState.c2_player.release();
                SharedState.c2_player = null;
            }
        } catch (Exception e) {
            XposedBridge.log("【VCAM】Camera2 stop c2_player: " + e);
        }
        try {
            if (SharedState.c2_player_1 != null) {
                SharedState.c2_player_1.stop();
                SharedState.c2_player_1.reset();
                SharedState.c2_player_1.release();
                SharedState.c2_player_1 = null;
            }
        } catch (Exception e) {
            XposedBridge.log("【VCAM】Camera2 stop c2_player_1: " + e);
        }
        try {
            if (SharedState.c2_hw_decode_obj != null) {
                SharedState.c2_hw_decode_obj.stopDecode();
                SharedState.c2_hw_decode_obj = null;
            }
        } catch (Exception e) {
            XposedBridge.log("【VCAM】Camera2 stop hw_decode1: " + e);
        }
        try {
            if (SharedState.c2_hw_decode_obj_1 != null) {
                SharedState.c2_hw_decode_obj_1.stopDecode();
                SharedState.c2_hw_decode_obj_1 = null;
            }
        } catch (Exception e) {
            XposedBridge.log("【VCAM】Camera2 stop hw_decode2: " + e);
        }
    }
}