package com.smartcollab.folder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FolderTreeTest {

    @Test
    @DisplayName("인접 리스트로 트리를 만들고 자식은 이름순으로 정렬한다")
    void buildsTree() {
        FolderTree tree = new FolderTree(List.of(
                new FolderNode(1L, "root", null),
                new FolderNode(3L, "나", 1L),
                new FolderNode(2L, "가", 1L),
                new FolderNode(4L, "하위", 2L)));
        List<FolderTree.TreeNode> roots = tree.roots("내 드라이브");
        assertThat(roots).hasSize(1);
        assertThat(roots.getFirst().name()).isEqualTo("내 드라이브");
        assertThat(roots.getFirst().children()).extracting(FolderTree.TreeNode::name).containsExactly("가", "나");
        assertThat(roots.getFirst().children().getFirst().children().getFirst().name()).isEqualTo("하위");
        assertThat(tree.pathOf(4L)).isEqualTo("/가/하위");
        assertThat(tree.pathOf(1L)).isEqualTo("/");
    }

    @Test
    @DisplayName("깊이 5,000 단계여도 재귀 없이 구성한다 (StackOverflow 없음)")
    void deepTree() {
        List<FolderNode> nodes = new ArrayList<>();
        nodes.add(new FolderNode(1L, "root", null));
        for (long i = 2; i <= 5000; i++) nodes.add(new FolderNode(i, "f" + i, i - 1));
        FolderTree tree = new FolderTree(nodes);
        FolderTree.TreeNode node = tree.roots(null).getFirst();
        int depth = 1;
        while (!node.children().isEmpty()) {
            node = node.children().getFirst();
            depth++;
        }
        assertThat(depth).isEqualTo(5000);
    }
}
