package ro.myfinance.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void mapsMaxUploadSizeExceededTo413() {
        ProblemDetail problem = handler.handleTooLarge(new MaxUploadSizeExceededException(25L * 1024 * 1024));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
        // Generic message — must not advertise the exact configured limit.
        assertThat(problem.getDetail()).isEqualTo("Uploaded file exceeds the maximum allowed size");
    }
}
