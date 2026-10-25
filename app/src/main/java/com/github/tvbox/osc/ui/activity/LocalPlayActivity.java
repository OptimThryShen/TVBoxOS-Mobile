package com.github.tvbox.osc.ui.activity;

import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;

import com.blankj.utilcode.util.GsonUtils;
import com.blankj.utilcode.util.SPUtils;
import com.blankj.utilcode.util.ToastUtils;
import com.github.tvbox.osc.base.BaseVbActivity;
import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.bean.VideoInfo;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.constant.CacheConst;
import com.github.tvbox.osc.databinding.ActivityLocalPlayBinding;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.player.MyVideoView;
import com.github.tvbox.osc.player.controller.LocalVideoController;
import com.github.tvbox.osc.receiver.BatteryReceiver;
import com.github.tvbox.osc.ui.dialog.AllLocalSeriesDialog;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.PlayerHelper;
import com.github.tvbox.osc.util.TsTimestampNormalizer;
import com.google.common.reflect.TypeToken;
import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.core.BasePopupView;
import com.lxj.xpopup.enums.PopupPosition;
import com.orhanobut.hawk.Hawk;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import xyz.doikki.videoplayer.player.ProgressManager;
import xyz.doikki.videoplayer.player.VideoView;

public class LocalPlayActivity extends BaseVbActivity<ActivityLocalPlayBinding> {


    private MyVideoView mVideoView;
    LocalVideoController mController;
    JSONObject mVodPlayerCfg;
    private List<VideoInfo> mVideoList = new ArrayList<>();
    private int mPosition;
    BatteryReceiver mBatteryReceiver = new BatteryReceiver();
    private BasePopupView mAllSeriesRightDialog;
    private final ExecutorService mPreparationExecutor = Executors.newSingleThreadExecutor();
    private Future<?> mPreparation;
    private int mPlayRequest;
    private boolean mForeground = true;
    @Override
    protected void init() {
        registerReceiver(mBatteryReceiver,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        mVideoView = mBinding.player;
        mVideoView.startFullScreen();
        Bundle bundle = getIntent().getExtras();
        String videoListJson =  bundle.getString("videoList");
        mVideoList = GsonUtils.fromJson(videoListJson, new TypeToken<List<VideoInfo>>(){}.getType());
        mPosition = bundle.getInt("position", 0);

        initController();
        initPlayerCfg();
        mVideoView.setVideoController(mController); //设置控制器
        play(false);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void refresh(RefreshEvent event) {
        if (event.type == RefreshEvent.TYPE_BATTERY_CHANGE && mController.mMyBatteryView!=null){
            mController.mMyBatteryView.updateBattery((int) event.obj);
        }
    }


    /**
     * 跳转到上/下一集,需重新播放
     */
    private void play(boolean fromSkip) {
        final VideoInfo videoInfo = mVideoList.get(mPosition);
        final String path = videoInfo.getPath();
        final File source = new File(path);
        final int request = ++mPlayRequest;
        if (mPreparation != null) mPreparation.cancel(true);
        // Release the previous engine before changing factories (e.g. MP4/IJK -> TS/Exo).
        mVideoView.release();
        dismissLoadingDialog();
        mController.setTitle(videoInfo.getDisplayName());
        if (!path.toLowerCase(Locale.ROOT).endsWith(".ts")) {
            startPreparedFile(source, path, fromSkip);
            return;
        }
        showLoadingDialog();
        mPreparation = mPreparationExecutor.submit(() -> {
            File prepared = source;
            String error = null;
            try {
                prepared = TsTimestampNormalizer.prepare(source, new File(getExternalCacheDir(), "ts-timeline"));
            } catch (Exception e) {
                error = e.getMessage();
            }
            if (Thread.currentThread().isInterrupted()) return;
            final File playable = prepared;
            final String failure = error;
            runOnUiThread(() -> {
                if (request != mPlayRequest || isFinishing() || isDestroyed()) return;
                dismissLoadingDialog();
                if (failure != null) ToastUtils.showLong("TS 时间轴处理失败，尝试原文件播放：" + failure);
                startPreparedFile(playable, path, fromSkip);
            });
        });
    }

    private void startPreparedFile(File file, String path, boolean fromSkip) {
        mVideoView.setUrl(Uri.fromFile(file).toString());

        mVideoView.setProgressManager(new ProgressManager() {
            @Override
            public void saveProgress(String url, long progress) {// 就本地视频页面用sp,其余用Hawk
                //有点本地文件确实总时长,设置下总时长,为什么用path,因为电影列表要通过媒体文件的path获取缓存的时长/进度,存取报纸缓存的key一直
                long duration = mVideoView.getDuration();
                if (duration > 0) SPUtils.getInstance(CacheConst.VIDEO_DURATION_SP).put(path, duration);
                SPUtils.getInstance(CacheConst.VIDEO_PROGRESS_SP).put(path, progress);
            }

            @Override
            public long getSavedProgress(String url) {
                return fromSkip ? 0 : Math.max(0, SPUtils.getInstance(CacheConst.VIDEO_PROGRESS_SP).getLong(path));
            }
        });

        JSONObject localPlayerCfg;
        try {
            localPlayerCfg = PlayerHelper.getLocalPlayerCfg(mVodPlayerCfg, path);
        } catch (JSONException e) {
            throw new IllegalStateException("Invalid local player configuration", e);
        }
        mController.setPlayerConfig(localPlayerCfg);
        PlayerHelper.updateCfg(mVideoView, localPlayerCfg);

        mVideoView.start();
        final int request = mPlayRequest;
        new Handler().postDelayed(() -> {
            if (request == mPlayRequest && mVideoView != null && mForeground
                    && mVideoView.getCurrentPlayState() == VideoView.STATE_PREPARED) {
                mVideoView.pause();
                mVideoView.resume();
            }
        }, 500);
    }

    private void initController() {
        mController = new LocalVideoController(this);
        mController.setListener(new LocalVideoController.VodControlListener() {

            @Override
            public void chooseSeries() {
                showAllSeriesDialog();
            }

            @Override
            public void playNext(boolean rmProgress) {
//                String preProgressKey = progressKey;
//                LocalPlayActivity.this.playNext(rmProgress);
                if (mPosition == mVideoList.size() - 1){
                    ToastUtils.showShort("当前已经是最后一集了");
                } else {
                    mPosition++;
                    play(true);
                }
            }

            @Override
            public void playPre() {
                //playPrevious();
                if (mPosition == 0){
                    ToastUtils.showShort("当前已经是第一集了");
                }else {
                    mPosition--;
                    play(true);
                }
            }

            @Override
            public void changeParse(ParseBean pb) {

            }

            @Override
            public void updatePlayerCfg() {

            }

            @Override
            public void replay(boolean replay) {

            }

            @Override
            public void errReplay() {

            }

            @Override
            public void selectSubtitle() {

            }

            @Override
            public void selectAudioTrack() {

            }

            @Override
            public void prepared() {
                if (!mForeground && mVideoView != null) mVideoView.pause();
            }

            @Override
            public void toggleFullScreen() {
                finish();
            }

            @Override
            public void exit() {
                finish();
            }
        });

    }

    void initPlayerCfg() {
        mVodPlayerCfg = new JSONObject();
        try {
            if (!mVodPlayerCfg.has("pl")) {
                mVodPlayerCfg.put("pl", Hawk.get(HawkConfig.PLAY_TYPE, 1));
            }
            if (!mVodPlayerCfg.has("pr")) {
                mVodPlayerCfg.put("pr", Hawk.get(HawkConfig.PLAY_RENDER, 0));
            }
            if (!mVodPlayerCfg.has("ijk")) {
                mVodPlayerCfg.put("ijk", Hawk.get(HawkConfig.IJK_CODEC, "硬解码"));
            }
            if (!mVodPlayerCfg.has("sc")) {
                mVodPlayerCfg.put("sc", Hawk.get(HawkConfig.PLAY_SCALE, 0));
            }
            if (!mVodPlayerCfg.has("sp")) {
                mVodPlayerCfg.put("sp", 1.0f);
            }
            if (!mVodPlayerCfg.has("st")) {
                mVodPlayerCfg.put("st", 0);
            }
            if (!mVodPlayerCfg.has("et")) {
                mVodPlayerCfg.put("et", 0);
            }
        } catch (Throwable th) {

        }
        mController.setPlayerConfig(mVodPlayerCfg);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mForeground = false;
        mVideoView.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mForeground = true;
        mVideoView.resume();
    }

    @Override
    protected void onDestroy() {
        mPlayRequest++;
        if (mPreparation != null) mPreparation.cancel(true);
        mPreparationExecutor.shutdownNow();
        dismissLoadingDialog();
        super.onDestroy();
        unregisterReceiver(mBatteryReceiver);
        if (mVideoView != null) {
            mVideoView.release();
            mVideoView = null;
        }
    }


    @Override
    public void onBackPressed() {
        if (!mVideoView.onBackPressed()) {
            super.onBackPressed();
        }
    }

    @Override
    public void finish() {
        super.finish();
        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_REFRESH, ""));
    }

    public void showAllSeriesDialog(){
        mAllSeriesRightDialog = new XPopup.Builder(this)
                .isViewMode(true)//隐藏导航栏(手势条)在dialog模式下会闪一下,改为view模式,但需处理onBackPress的隐藏,下方同理
                .hasNavigationBar(false)
                .popupHeight(com.blankj.utilcode.util.ScreenUtils.getScreenHeight())
                .popupPosition(PopupPosition.Right)
                .asCustom(new AllLocalSeriesDialog(this, convertLocalVideo(), (position, text) -> {
                    mPosition = position;
                    play(true);
                }));
        mAllSeriesRightDialog.show();
    }

    private List<VodInfo.VodSeries> convertLocalVideo(){
        List<VodInfo.VodSeries> seriesList = new ArrayList<>();
        for (VideoInfo local : mVideoList) {
            VodInfo.VodSeries vodSeries = new VodInfo.VodSeries(local.getDisplayName(), local.getPath());
            vodSeries.selected = (Objects.equals(mVideoList.get(mPosition).getPath(), vodSeries.url));
            seriesList.add(vodSeries);
        }
        return seriesList;
    }
}