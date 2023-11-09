package ee.carlrobert.codegpt.completions;

import com.intellij.openapi.diagnostic.Logger;
import ee.carlrobert.codegpt.conversations.Conversation;
import ee.carlrobert.codegpt.conversations.message.Message;
import ee.carlrobert.codegpt.settings.state.SettingsState;
import ee.carlrobert.codegpt.telemetry.TelemetryAction;
import ee.carlrobert.llm.client.openai.completion.ErrorDetails;
import ee.carlrobert.llm.client.you.completion.YouCompletionEventListener;
import ee.carlrobert.llm.client.you.completion.YouSerpResult;
import ee.carlrobert.llm.completion.CompletionEventListener;
import java.util.List;
import javax.swing.SwingWorker;
import okhttp3.sse.EventSource;
import org.jetbrains.annotations.NotNull;

public class CompletionRequestHandler {

  private static final Logger LOG = Logger.getInstance(CompletionRequestHandler.class);

  private final StringBuilder messageBuilder = new StringBuilder();
  private final boolean useContextualSearch;
  private final ToolWindowCompletionEventListener toolWindowCompletionEventListener;
  private SwingWorker<Void, String> swingWorker;
  private EventSource eventSource;

  public CompletionRequestHandler(
      boolean useContextualSearch,
      ToolWindowCompletionEventListener toolWindowCompletionEventListener) {
    this.useContextualSearch = useContextualSearch;
    this.toolWindowCompletionEventListener = toolWindowCompletionEventListener;
  }

  public void call(Conversation conversation, Message message, boolean isRetry) {
    swingWorker = new CompletionRequestWorker(conversation, message, isRetry);
    swingWorker.execute();
  }

  public void cancel() {
    if (eventSource != null) {
      eventSource.cancel();
    }
    swingWorker.cancel(true);
  }

  private EventSource startCall(
      @NotNull Conversation conversation,
      @NotNull Message message,
      boolean retry,
      CompletionEventListener eventListener) {
    try {
      return CompletionRequestService.getInstance()
          .getChatCompletionAsync(conversation, message, retry, useContextualSearch, eventListener);
    } catch (Throwable t) {
      toolWindowCompletionEventListener.handleError(new ErrorDetails("Something went wrong"), t);
      throw t;
    }
  }

  private class CompletionRequestWorker extends SwingWorker<Void, String> {

    private final Conversation conversation;
    private final Message message;
    private final boolean isRetry;

    public CompletionRequestWorker(Conversation conversation, Message message, boolean isRetry) {
      this.conversation = conversation;
      this.message = message;
      this.isRetry = isRetry;
    }

    protected Void doInBackground() {
      var settings = SettingsState.getInstance();
      try {
        eventSource = startCall(
            conversation,
            message,
            isRetry,
            new YouRequestCompletionEventListener());
      } catch (TotalUsageExceededException e) {
        toolWindowCompletionEventListener.handleTokensExceeded(conversation, message);
      } finally {
        sendInfo(settings);
      }
      return null;
    }

    protected void process(List<String> chunks) {
      message.setResponse(messageBuilder.toString());
      for (String text : chunks) {
        messageBuilder.append(text);
        toolWindowCompletionEventListener.handleMessage(text);
      }
    }

    class YouRequestCompletionEventListener implements YouCompletionEventListener {

      @Override
      public void onSerpResults(List<YouSerpResult> results) {
        toolWindowCompletionEventListener.handleSerpResults(results, message);
      }

      @Override
      public void onMessage(String message) {
        publish(message);
      }

      @Override
      public void onComplete(StringBuilder messageBuilder) {
        toolWindowCompletionEventListener.handleCompleted(messageBuilder.toString(), message,
            conversation, isRetry);
      }

      @Override
      public void onError(ErrorDetails error, Throwable ex) {
        try {
          toolWindowCompletionEventListener.handleError(error, ex);
        } finally {
          sendError(error, ex);
        }
      }
    }

    private void sendInfo(SettingsState settings) {
      TelemetryAction.COMPLETION.createActionMessage()
          .property("conversationId", conversation.getId().toString())
          .property("model", conversation.getModel())
          .property("service", settings.getSelectedService().getCode().toLowerCase())
          .send();
    }

    private void sendError(ErrorDetails error, Throwable ex) {
      var telemetryMessage = TelemetryAction.COMPLETION_ERROR.createActionMessage();
      if ("insufficient_quota".equals(error.getCode())) {
        telemetryMessage
            .property("type", "USER")
            .property("code", "INSUFFICIENT_QUOTA");
      } else {
        telemetryMessage
            .property("conversationId", conversation.getId().toString())
            .property("model", conversation.getModel())
            .error(new RuntimeException(error.toString(), ex));
      }
      telemetryMessage.send();
    }
  }
}
