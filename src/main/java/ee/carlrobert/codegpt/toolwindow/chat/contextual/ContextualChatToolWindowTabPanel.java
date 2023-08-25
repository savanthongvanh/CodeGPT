package ee.carlrobert.codegpt.toolwindow.chat.contextual;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import ee.carlrobert.codegpt.user.UserManager;
import ee.carlrobert.codegpt.user.auth.AuthenticationNotifier;
import ee.carlrobert.codegpt.user.auth.SignedOutNotifier;
import ee.carlrobert.codegpt.conversations.Conversation;
import ee.carlrobert.codegpt.conversations.message.Message;
import ee.carlrobert.codegpt.embeddings.CodebaseIndexingCompletedNotifier;
import ee.carlrobert.codegpt.toolwindow.chat.BaseChatToolWindowTabPanel;
import javax.swing.JComponent;
import org.jetbrains.annotations.NotNull;

public class ContextualChatToolWindowTabPanel extends BaseChatToolWindowTabPanel {

  public ContextualChatToolWindowTabPanel(@NotNull Project project) {
    super(project, true);
    displayLandingView();
    userTextArea.setTextAreaEnabled(UserManager.getInstance().isSubscribed());

    project.getMessageBus()
        .connect()
        .subscribe(CodebaseIndexingCompletedNotifier.INDEXING_COMPLETED_TOPIC,
            (CodebaseIndexingCompletedNotifier) () -> userTextArea.setTextAreaEnabled(UserManager.getInstance().isSubscribed()));

    var messageBusConnection = ApplicationManager.getApplication().getMessageBus().connect();
    messageBusConnection.subscribe(AuthenticationNotifier.AUTHENTICATION_TOPIC,
        (AuthenticationNotifier) () -> userTextArea.setTextAreaEnabled(UserManager.getInstance().isSubscribed()));
    messageBusConnection.subscribe(SignedOutNotifier.SIGNED_OUT_TOPIC, (SignedOutNotifier) () -> userTextArea.setTextAreaEnabled(false));
  }

  @Override
  protected JComponent getLandingView() {
    return new ContextualChatToolWindowLandingPanel(project, (prompt) -> {
      var message = new Message(prompt);
      if (conversation == null) {
        startNewConversation(message);
      } else {
        sendMessage(message);
      }
    });
  }

  @Override
  public void displayConversation(Conversation conversation) {
  }
}