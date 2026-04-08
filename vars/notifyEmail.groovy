def call(Map config = [:]) {
    def status = config.status ?: currentBuild.currentResult
    def recipient = config.recipient ?: 'vignesh.rajendran@protonmail.com'
    def subject = config.subject ?: "${env.JOB_NAME} - Build #${env.BUILD_NUMBER} - ${status}!"

    def duration = currentBuild.durationString?.replace(' and counting', '') ?: 'unknown'

    def body = """
    <html>
    <body style="font-family: monospace; background: #1a1a2e; color: #e0e0e0; padding: 20px;">
        <div style="max-width: 600px; margin: 0 auto; border: 1px solid #333; border-radius: 8px; padding: 20px; background: #16213e;">
            <h2 style="color: ${status == 'SUCCESS' ? '#4caf50' : '#f44336'};">${status}</h2>
            <table style="width: 100%; border-collapse: collapse;">
                <tr><td style="padding: 8px; color: #888;">Job</td><td style="padding: 8px;">${env.JOB_NAME}</td></tr>
                <tr><td style="padding: 8px; color: #888;">Build</td><td style="padding: 8px;">#${env.BUILD_NUMBER}</td></tr>
                <tr><td style="padding: 8px; color: #888;">Status</td><td style="padding: 8px;">${status}</td></tr>
                <tr><td style="padding: 8px; color: #888;">Duration</td><td style="padding: 8px;">${duration}</td></tr>
                <tr><td style="padding: 8px; color: #888;">Node</td><td style="padding: 8px;">${env.NODE_NAME ?: 'N/A'}</td></tr>
            </table>
            <p style="margin-top: 16px;">
                <a href="${env.BUILD_URL}" style="color: #64b5f6;">View Build</a> |
                <a href="${env.BUILD_URL}console" style="color: #64b5f6;">Console Output</a>
            </p>
            <hr style="border-color: #333;">
            <p style="color: #666; font-size: 12px;">Jenkins CI/CD — linuxer.dev</p>
        </div>
    </body>
    </html>
    """

    emailext(
        to: recipient,
        subject: subject,
        body: body,
        mimeType: 'text/html'
    )
}
