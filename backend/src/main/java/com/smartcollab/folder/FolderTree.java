package com.smartcollab.folder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 한 번에 읽은 폴더 목록(인접 리스트)으로 트리와 경로를 메모리에서 계산합니다. 시간 복잡도 O(n).
 * v1 은 폴더마다 하위 목록을 지연 로딩해 폴더 수만큼 SQL 이 실행되었습니다(N+1).
 */
public final class FolderTree {

    private final Map<Long, FolderNode> byId = new HashMap<>();
    private final Map<Long, List<FolderNode>> childrenByParent = new HashMap<>();

    public FolderTree(List<FolderNode> nodes) {
        for (FolderNode node : nodes) {
            byId.put(node.id(), node);
        }
        for (FolderNode node : nodes) {
            if (node.parentId() != null && byId.containsKey(node.parentId())) {
                childrenByParent.computeIfAbsent(node.parentId(), k -> new ArrayList<>()).add(node);
            }
        }
        childrenByParent.values().forEach(list -> list.sort(Comparator.comparing(FolderNode::name, String.CASE_INSENSITIVE_ORDER)));
    }

    public List<TreeNode> roots(String rootDisplayName) {
        return byId.values().stream()
                .filter(n -> n.parentId() == null || !byId.containsKey(n.parentId()))
                .sorted(Comparator.comparing(FolderNode::id))
                .map(n -> build(n, rootDisplayName))
                .toList();
    }

    private TreeNode build(FolderNode node, String rootDisplayName) {
        // 깊은 트리에서도 스택 오버플로가 나지 않도록 반복(DFS)으로 구성합니다.
        TreeNode root = new TreeNode(node.id(), rootDisplayName != null ? rootDisplayName : node.name(), new ArrayList<>());
        record Frame(FolderNode node, TreeNode tree) {
        }
        ArrayList<Frame> stack = new ArrayList<>();
        stack.add(new Frame(node, root));
        while (!stack.isEmpty()) {
            Frame frame = stack.removeLast();
            for (FolderNode child : childrenByParent.getOrDefault(frame.node().id(), List.of())) {
                TreeNode childTree = new TreeNode(child.id(), child.name(), new ArrayList<>());
                frame.tree().children().add(childTree);
                stack.add(new Frame(child, childTree));
            }
        }
        return root;
    }

    /** 루트부터 해당 폴더까지의 이름 경로 (예: "/보고서/2025"). 루트 이름은 생략합니다. */
    public String pathOf(Long folderId) {
        List<String> names = new ArrayList<>();
        FolderNode current = byId.get(folderId);
        int guard = 0;
        while (current != null && current.parentId() != null && guard++ < 10_000) {
            names.add(current.name());
            current = byId.get(current.parentId());
        }
        if (names.isEmpty()) {
            return "/";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = names.size() - 1; i >= 0; i--) {
            sb.append('/').append(names.get(i));
        }
        return sb.toString();
    }

    public List<Long> ids() {
        return List.copyOf(byId.keySet());
    }

    public record TreeNode(Long id, String name, List<TreeNode> children) {
    }
}
