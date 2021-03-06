package cn.bearever.likemosaic.call

import android.content.Context
import android.util.Log
import cn.bearever.likemosaic.BaseEventHandler
import cn.bearever.likemosaic.MosaiApplication
import cn.bearever.likemosaic.RtcPacketObserver
import cn.bearever.likemosaic.UidUtil
import cn.bearever.likemosaic.bean.MessageBean
import cn.bearever.likemosaic.bean.SelectTopicBean
import cn.bearever.likemosaic.bean.TopicBean
import cn.bearever.likemosaic.bean.TopicListResultBean
import cn.bearever.mingbase.BaseCallback
import cn.bearever.mingbase.app.mvp.BasePresenterIml
import io.agora.rtc.RtcEngine
import io.agora.rtc.mediaio.IVideoSink
import io.agora.rtc.video.VideoEncoderConfiguration
import java.util.*

/**
 * @author luoming
 * @date 2020/4/16
 */
class VideoCallPresenter(view: VideoCallContact.View?, context: Context?) :
        BasePresenterIml<VideoCallContact.View?, VideoCallContact.Model?>(view, context), VideoCallContact.Presenter {

    companion object {
        private const val TAG = "VideoCallPresenter"
    }

    private var mRtcEngine: RtcEngine? = null
    private var mChannel = ""

    //我对对方的好感度
    private var mLikeCountMe2Other = 50

    //对方对我的好感度
    private var mLikeCountOther2Me = 50
    private var mTimerCount = 0
    private val LOCK_LIKE_COUNT = Any()
    private lateinit var mTimer: Timer

    private fun initLikeTimer() {
        //每一秒钟将mLikeCount-1
        mTimer = Timer()
        mTimer.schedule(object : TimerTask() {
            override fun run() {
                synchronized(LOCK_LIKE_COUNT) {
                    mLikeCountMe2Other--
                    mLikeCountOther2Me--
                }

                view?.refreshLike(mLikeCountOther2Me)

                if (mLikeCountMe2Other <= 0) {
                    //好感度为0，聊天结束
                    view?.localLikeEmpty()
                    mTimer.cancel()
                    return
                }
                if (mLikeCountOther2Me <= 0) {
                    view?.onUserLeft()
                    mTimer.cancel()
                }

                mTimerCount++
                if (mTimerCount == 5) {
                    view?.showQuitBtn()
                }

                if (mLikeCountOther2Me == 10) {
                    view?.showNote("对方对你的好感度降至冰点了！")
                }

                LikeManager.getInstance().setLikeCountMe2Other(mLikeCountMe2Other)
                LikeManager.getInstance().setLikeCountOther2Me(mLikeCountOther2Me)
                sendLike()
            }
        }, 1000, 1000)
    }

    fun initEngineAndJoinChannel() {
        Log.e("开始调用了Presenter", "-------------")
        initializeEngine()
        setupVideoConfig()

    }

    private var mRtcHandler = object : BaseEventHandler() {
        override fun onFirstRemoteVideoDecoded(uid: Int, width: Int, height: Int, elapsed: Int) {
            super.onFirstRemoteVideoDecoded(uid, width, height, elapsed)
            Log.e("加入房间啦", "---------")
            view?.onUserJoin(uid)
            initLikeTimer()
        }
    }

    private fun initializeEngine() {
        val app = context.applicationContext as MosaiApplication
        mRtcEngine = app.rtcEngine()
        app.registerHandler(mRtcHandler)
    }

    private fun setupVideoConfig() {
        mRtcEngine?.enableVideo()
        mRtcEngine?.setVideoEncoderConfiguration(VideoEncoderConfiguration(
                VideoEncoderConfiguration.VD_640x480,
                VideoEncoderConfiguration.FRAME_RATE.FRAME_RATE_FPS_15,
                VideoEncoderConfiguration.STANDARD_BITRATE,
                VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_FIXED_PORTRAIT))
    }

    override fun initModel() {
        mModel = VideoCallModel(context)
        mModel?.registerMessage { message ->
            //接收到对方发送的消息
            if (message?.channel != mChannel) {
                return@registerMessage
            }

            when (message.key) {
                MessageBean.KEY_SELECT_TOPIC -> {
                    view?.receiveSelectTag(message.data as SelectTopicBean?)
                    view?.showNote(message.text)
                }

                MessageBean.KEY_REFRESH_TOPIC -> {
                    view?.refreshTags(message.data as ArrayList<TopicBean>)
                    view?.startRefreshAnimation(false)
                }

                MessageBean.KEY_REMOTE_LIKE_CHANGE -> {
                    val likeCount = message.data as Int
                    if (likeCount - mLikeCountOther2Me > 3) {
                        if (mLikeCountOther2Me < 100 && likeCount > 100) {
                            view?.showNote("对方对你的好感度增加了！")
                        } else if (mLikeCountOther2Me < 200 && likeCount > 200) {
                            view?.showNote("对方对你的好感度增加了！")
                        } else if (mLikeCountOther2Me < 300 && likeCount > 300) {
                            view?.showNote("对方对你的好感度报表啦！")
                        }
                    }
                    mLikeCountOther2Me = likeCount
                    view?.refreshLike(mLikeCountOther2Me)
                }

                MessageBean.KEY_QUIT_ROOM -> {
                    view?.onUserLeft()
                }
            }
//            if (!TextUtils.isEmpty(message.text)) {
//                ToastUtil.show(message.text)
//            }
        }
    }

    override fun setLocalVideoRenderer(sink: IVideoSink) {
        mRtcEngine?.setLocalVideoRenderer(sink)
        mRtcEngine?.startPreview()
    }

    override fun setRemoteVideoRenderer(uid: Int, sink: IVideoSink) {
        mRtcEngine?.setRemoteVideoRenderer(uid, sink)
    }

    override fun joinRoom(channel: String?, rtcToken: String?, rtmToken: String?, remoteUid: String?) {
        mChannel = channel ?: ""
        mModel?.loginRtm(rtmToken, channel, remoteUid)
        mRtcEngine?.joinChannelWithUserAccount(rtcToken, channel, UidUtil.getUid(context))
    }

    override fun quitRoom() {
        //发送离开频道的消息
        sendQuitRoomMessage()
        val app = context.applicationContext as MosaiApplication
        app.unregisterHandler(mRtcHandler)
        //
        mRtcEngine?.leaveChannel()
        mModel?.logoutRtm()
        mTimer.cancel()
    }

    private fun sendQuitRoomMessage() {
        val message = MessageBean<String>(mChannel)
        message.key = MessageBean.KEY_QUIT_ROOM
        mModel?.sendMessage(message)
    }

    override fun muteAudio(mute: Boolean) {
        mRtcEngine?.muteLocalAudioStream(mute)
    }

    override fun selectTopic(topicBean: TopicBean, isSelect: Boolean) {
        val message = MessageBean<SelectTopicBean>(mChannel)
        message.key = MessageBean.KEY_SELECT_TOPIC
        val selectTopicBean = SelectTopicBean()
        selectTopicBean.id = topicBean.id
        selectTopicBean.selected = isSelect
        message.data = selectTopicBean
        if (isSelect) {
            message.text = "对方选择了【" + topicBean.text + "】话题"
        } else {
            message.text = "对方取消了【" + topicBean.text + "】话题"
        }
        mModel?.sendMessage(message)
    }

    override fun addLike() {
        synchronized(LOCK_LIKE_COUNT) {
            mLikeCountMe2Other += 2
        }
    }

    private fun sendLike() {
        val message = MessageBean<Int>(mChannel)
        message.key = MessageBean.KEY_REMOTE_LIKE_CHANGE
        message.data = mLikeCountMe2Other
        mModel?.sendMessage(message)
    }

    override fun refreshTopics() {
        mModel?.getTopics(object : BaseCallback<TopicListResultBean>() {
            override fun suc(data: TopicListResultBean) {
                view?.refreshTags(data.list)
                val message = MessageBean<ArrayList<TopicBean>>(mChannel)
                message.key = MessageBean.KEY_REFRESH_TOPIC
                message.data = data.list
                message.text = "对方刷新了话题区"
                mModel?.sendMessage(message)
            }
        })
    }
}