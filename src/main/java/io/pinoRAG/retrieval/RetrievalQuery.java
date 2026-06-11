package io.pinoRAG.retrieval;

// Carries both representations of the user question plus the caller
// identity used for per-document ACL enforcement. Building this once at
// the controller boundary keeps the Retriever interface narrow.
public record RetrievalQuery(
        String text,
        float[] embedding,
        // Caller identity within the tenant. null subject + empty groups
        // means "no caller identity"; in that case only docs flagged
        // is_public or with no owner_subject are visible.
        String subject,
        String[] groups
) {

    public RetrievalQuery(String text, float[] embedding) {
        this(text, embedding, null, new String[0]);
    }
}
