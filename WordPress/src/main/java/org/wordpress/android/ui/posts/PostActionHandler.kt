package org.wordpress.android.ui.posts

import android.content.Intent
import org.wordpress.android.R
import org.wordpress.android.fluxc.Dispatcher
import org.wordpress.android.fluxc.generated.PostActionBuilder
import org.wordpress.android.fluxc.model.LocalOrRemoteId.LocalId
import org.wordpress.android.fluxc.model.PostModel
import org.wordpress.android.fluxc.model.SiteModel
import org.wordpress.android.fluxc.model.post.PostStatus.DRAFT
import org.wordpress.android.fluxc.store.PostStore
import org.wordpress.android.fluxc.store.PostStore.RemotePostPayload
import org.wordpress.android.ui.notifications.utils.PendingDraftsNotificationsUtils
import org.wordpress.android.ui.pages.SnackbarMessageHolder
import org.wordpress.android.ui.posts.CriticalPostActionTracker.CriticalPostAction.DELETING_POST
import org.wordpress.android.ui.posts.CriticalPostActionTracker.CriticalPostAction.RESTORING_POST
import org.wordpress.android.ui.posts.CriticalPostActionTracker.CriticalPostAction.TRASHING_POST
import org.wordpress.android.ui.posts.PostListAction.DismissPendingNotification
import org.wordpress.android.ui.posts.PostListAction.PreviewPost
import org.wordpress.android.ui.posts.PostListAction.RetryUpload
import org.wordpress.android.ui.posts.PostListAction.ViewPost
import org.wordpress.android.ui.posts.PostListAction.ViewStats
import org.wordpress.android.ui.posts.PostUploadAction.CancelPostAndMediaUpload
import org.wordpress.android.ui.posts.PostUploadAction.EditPostResult
import org.wordpress.android.ui.posts.PostUploadAction.PublishPost
import org.wordpress.android.ui.uploads.UploadService
import org.wordpress.android.util.ToastUtils.Duration
import org.wordpress.android.viewmodel.helpers.ToastMessageHolder
import org.wordpress.android.widgets.PostListButtonType
import org.wordpress.android.widgets.PostListButtonType.BUTTON_BACK
import org.wordpress.android.widgets.PostListButtonType.BUTTON_DELETE
import org.wordpress.android.widgets.PostListButtonType.BUTTON_EDIT
import org.wordpress.android.widgets.PostListButtonType.BUTTON_MORE
import org.wordpress.android.widgets.PostListButtonType.BUTTON_MOVE_TO_DRAFT
import org.wordpress.android.widgets.PostListButtonType.BUTTON_PREVIEW
import org.wordpress.android.widgets.PostListButtonType.BUTTON_PUBLISH
import org.wordpress.android.widgets.PostListButtonType.BUTTON_RETRY
import org.wordpress.android.widgets.PostListButtonType.BUTTON_STATS
import org.wordpress.android.widgets.PostListButtonType.BUTTON_SUBMIT
import org.wordpress.android.widgets.PostListButtonType.BUTTON_SYNC
import org.wordpress.android.widgets.PostListButtonType.BUTTON_TRASH
import org.wordpress.android.widgets.PostListButtonType.BUTTON_VIEW

/**
 * This is a temporary class to make the PostListViewModel more manageable. Please feel free to refactor it any way
 * you see fit.
 */
class PostActionHandler(
    private val dispatcher: Dispatcher,
    private val site: SiteModel,
    private val postStore: PostStore,
    private val postListDialogHelper: PostListDialogHelper,
    private val doesPostHaveUnhandledConflict: (PostModel) -> Boolean,
    private val triggerPostListAction: (PostListAction) -> Unit,
    private val triggerPostUploadAction: (PostUploadAction) -> Unit,
    private val invalidateList: () -> Unit,
    private val checkNetworkConnection: () -> Boolean,
    private val showSnackbar: (SnackbarMessageHolder) -> Unit,
    private val showToast: (ToastMessageHolder) -> Unit
) {
    private val criticalPostActionTracker = CriticalPostActionTracker(listener = {
        invalidateList.invoke()
    })

    fun handlePostButton(buttonType: PostListButtonType, post: PostModel) {
        when (buttonType) {
            BUTTON_EDIT -> editPostButtonAction(site, post)
            BUTTON_RETRY -> triggerPostListAction.invoke(RetryUpload(post))
            BUTTON_MOVE_TO_DRAFT -> {
                moveTrashedPostToDraft(post)
            }
            BUTTON_SUBMIT, BUTTON_SYNC, BUTTON_PUBLISH -> {
                postListDialogHelper.showPublishConfirmationDialog(post)
            }
            BUTTON_VIEW -> triggerPostListAction.invoke(ViewPost(site, post))
            BUTTON_PREVIEW -> triggerPostListAction.invoke(PreviewPost(site, post))
            BUTTON_STATS -> triggerPostListAction.invoke(ViewStats(site, post))
            BUTTON_TRASH -> {
                trashPost(post)
            }
            BUTTON_DELETE -> {
                postListDialogHelper.showDeletePostConfirmationDialog(post)
            }
            BUTTON_MORE -> {
            } // do nothing - ui will show a popup window
            BUTTON_BACK -> TODO("will be removed during PostViewHolder refactoring")
        }
    }

    fun newPost() {
        triggerPostListAction(PostListAction.NewPost(site))
    }

    fun handleEditPostResult(data: Intent?) {
        val localPostId = data?.getIntExtra(EditPostActivity.EXTRA_POST_LOCAL_ID, 0)
        if (localPostId == null || localPostId == 0) {
            return
        }
        val post = postStore.getPostByLocalPostId(localPostId)
        if (post != null) {
            triggerPostUploadAction(EditPostResult(site, post, data) { publishPost(localPostId) })
        }
    }

    fun publishPost(localPostId: Int) {
        val post = postStore.getPostByLocalPostId(localPostId)
        if (post != null) {
            triggerPostUploadAction.invoke(PublishPost(dispatcher, site, post))
        }
    }

    private fun moveTrashedPostToDraft(post: PostModel) {
        /*
         * We need network connection to move a post to remote draft. We can technically move it to the local drafts
         * but that'll leave the trashed post in the remote which can be confusing.
         */
        if (!checkNetworkConnection.invoke()) {
            return
        }
        post.status = DRAFT.toString()
        dispatcher.dispatch(PostActionBuilder.newPushPostAction(RemotePostPayload(post, site)))

        val snackBarHolder = SnackbarMessageHolder(
                messageRes = R.string.post_moving_to_draft,
                buttonTitleRes = R.string.undo,
                buttonAction = {
                    trashPost(post)
                }
        )
        showSnackbar.invoke(snackBarHolder)
    }

    private fun editPostButtonAction(site: SiteModel, post: PostModel) {
        // first of all, check whether this post is in Conflicted state.
        if (doesPostHaveUnhandledConflict.invoke(post)) {
            postListDialogHelper.showConflictedPostResolutionDialog(post)
            return
        }

        editPost(site, post)
    }

    private fun editPost(site: SiteModel, post: PostModel) {
        if (UploadService.isPostUploadingOrQueued(post)) {
            // If the post is uploading media, allow the media to continue uploading, but don't upload the
            // post itself when they finish (since we're about to edit it again)
            UploadService.cancelQueuedPostUpload(post)
        }
        triggerPostListAction.invoke(PostListAction.EditPost(site, post))
    }

    fun deletePost(localPostId: Int) {
        // If post doesn't exist, nothing else to do
        val post = postStore.getPostByLocalPostId(localPostId) ?: return
        criticalPostActionTracker.add(LocalId(post.id), DELETING_POST)

        when {
            post.isLocalDraft -> {
                val pushId = PendingDraftsNotificationsUtils.makePendingDraftNotificationId(post.id)
                triggerPostListAction(DismissPendingNotification(pushId))
                dispatcher.dispatch(PostActionBuilder.newRemovePostAction(post))
            }
            else -> {
                dispatcher.dispatch(PostActionBuilder.newDeletePostAction(RemotePostPayload(post, site)))
            }
        }
    }

    /**
     * This function handles a post being deleted and removed. Since deleting remote posts will trigger both delete
     * and remove actions we only want to remove the critical action when the post is actually successfully removed.
     *
     * It's possible to separate these into two methods that handles delete and remove. However, the fact that they
     * follow the same approach and the tricky nature of delete action makes combining the actions like so makes our
     * expectations clearer.
     */
    fun handlePostDeletedOrRemoved(localPostId: LocalId, isRemoved: Boolean, isError: Boolean) {
        if (criticalPostActionTracker.get(localPostId) != DELETING_POST) {
            /*
             * This is an unexpected action and either it has already been handled or another critical action has
             * been performed. In either case, safest action is to just ignore it.
             */
            return
        }
        if (isError) {
            showToast.invoke(ToastMessageHolder(R.string.error_deleting_post, Duration.SHORT))
        }
        if (isRemoved) {
            criticalPostActionTracker.remove(localPostId = localPostId, criticalPostAction = DELETING_POST)
        }
    }

    private fun trashPost(post: PostModel) {
        // We need network connection to trash a post
        if (!checkNetworkConnection()) {
            return
        }

        showSnackbar.invoke(SnackbarMessageHolder(R.string.post_trashing))
        criticalPostActionTracker.add(localPostId = LocalId(post.id), criticalPostAction = TRASHING_POST)

        triggerPostUploadAction.invoke(CancelPostAndMediaUpload(post))
        dispatcher.dispatch(PostActionBuilder.newDeletePostAction(RemotePostPayload(post, site)))
    }

    fun handlePostTrashed(localPostId: LocalId, isError: Boolean) {
        if (criticalPostActionTracker.get(localPostId) != TRASHING_POST) {
            /*
             * This is an unexpected action and either it has already been handled or another critical action has
             * been performed. In either case, safest action is to just ignore it.
             */
            return
        }
        criticalPostActionTracker.remove(localPostId = localPostId, criticalPostAction = TRASHING_POST)
        if (isError) {
            showToast.invoke(ToastMessageHolder(R.string.error_deleting_post, Duration.SHORT))
        } else {
            showSnackbar.invoke(SnackbarMessageHolder(messageRes = R.string.post_trashed))
            val snackBarHolder = SnackbarMessageHolder(
                    messageRes = R.string.post_trashed,
                    buttonTitleRes = R.string.undo,
                    buttonAction = {
                        val post = postStore.getPostByLocalPostId(localPostId.value)
                        if (post != null) {
                            restorePost(post)
                        }
                    }
            )
            showSnackbar.invoke(snackBarHolder)
        }
    }

    private fun restorePost(post: PostModel) {
        // We need network connection to restore a post
        if (!checkNetworkConnection.invoke()) {
            return
        }
        showSnackbar.invoke(SnackbarMessageHolder(messageRes = R.string.post_restoring))
        criticalPostActionTracker.add(localPostId = LocalId(post.id), criticalPostAction = RESTORING_POST)
        dispatcher.dispatch(PostActionBuilder.newRestorePostAction(RemotePostPayload(post, site)))
    }

    fun handlePostRestored(localPostId: LocalId, isError: Boolean) {
        if (criticalPostActionTracker.get(localPostId) != RESTORING_POST) {
            /*
             * This is an unexpected action and either it has already been handled or another critical action has
             * been performed. In either case, safest action is to just ignore it.
             */
            return
        }
        criticalPostActionTracker.remove(localPostId = localPostId, criticalPostAction = RESTORING_POST)
        if (isError) {
            showToast.invoke(ToastMessageHolder(R.string.error_restoring_post, Duration.SHORT))
        } else {
            showSnackbar.invoke(SnackbarMessageHolder(messageRes = R.string.post_restored))
        }
    }

    fun isPerformingCriticalAction(localPostId: LocalId): Boolean {
        return criticalPostActionTracker.contains(localPostId)
    }
}
