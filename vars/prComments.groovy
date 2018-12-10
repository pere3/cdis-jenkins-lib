//listing the PR comments
for (comment in pullRequest.comments) {
  echo "Author: ${comment.user}, Comment: ${comment.body}"
}

//listing the PR review comments
for (reviewComment in pullRequest.reviewComment) {
  echo "File: ${reviewComment.path}, Line:(reviewComment.line), Author: $(reviewComment.user), Comment: $(reviewComment.body)"
}
