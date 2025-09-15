const Dashboard = ({ user }) => {
    const [viewContext, setViewContext] = React.useState({ type: 'personal', id: null, name: ' 내 드라이브 ' });
    const [personalFolders, setPersonalFolders] = React.useState([]);
    const [currentItems, setCurrentItems] = React.useState([]);
    const [myTeams, setMyTeams] = React.useState([]);
    const [selectedItems, setSelectedItems] = React.useState([]);
    const [selectedTeamContext, setSelectedTeamContext] = React.useState(null);
    const [currentUserPermissions, setCurrentUserPermissions] = React.useState({
        canEdit: true, canDelete: true, canInvite: true, isTeamLeader: true
    });
    const [chatMessages, setChatMessages] = React.useState([]);
    const [editingFile, setEditingFile] = React.useState(null);
    const [sortConfig, setSortConfig] = React.useState({ key: 'type', direction: 'ascending' });
    const [breadcrumbPath, setBreadcrumbPath] = React.useState([]);
    const [searchQuery, setSearchQuery] = React.useState("");
    const [searchResults, setSearchResults] = React.useState(null);

    const [unreadChats, setUnreadChats] = React.useState(new Set());
    const [activeCollabTab, setActiveCollabTab] = React.useState('members');
    const activeCollabTabRef = React.useRef(activeCollabTab);
    React.useEffect(() => { activeCollabTabRef.current = activeCollabTab; }, [activeCollabTab]);

    const stompClientsRef = React.useRef(new Map());
    const selectedTeamContextRef = React.useRef(selectedTeamContext);
    React.useEffect(() => { selectedTeamContextRef.current = selectedTeamContext; }, [selectedTeamContext]);
    React.useEffect(() => { activeCollabTabRef.current = activeCollabTab; }, [activeCollabTab]);

    const [isNewFolderModalOpen, setIsNewFolderModalOpen] = React.useState(false);
    const [isRenameModalOpen, setIsRenameModalOpen] = React.useState(false);
    const [itemToRename, setItemToRename] = React.useState(null);
    const [isMoveModalOpen, setIsMoveModalOpen] = React.useState(false);
    const [isCopyModalOpen, setIsCopyModalOpen] = React.useState(false);
    const [isShareModalOpen, setIsShareModalOpen] = React.useState(false);
    const [fileToShare, setFileToShare] = React.useState(null);
    const [isNewTeamModalOpen, setIsNewTeamModalOpen] = React.useState(false);
    const [isInviteModalOpen, setIsInviteModalOpen] = React.useState(false);
    const [isPermissionsModalOpen, setIsPermissionsModalOpen] = React.useState(false);
    const [memberToEdit, setMemberToEdit] = React.useState(null);
    const [isHistoryModalOpen, setIsHistoryModalOpen] = React.useState(false);
    const [fileForHistory, setFileForHistory] = React.useState(null);
    const [isPreviewModalOpen, setIsPreviewModalOpen] = React.useState(false);
    const [fileToPreview, setFileToPreview] = React.useState(null);
    const [isAiModalOpen, setIsAiModalOpen] = React.useState(false);
    const [aiModalTitle, setAiModalTitle] = React.useState('');
    const [aiResultContent, setAiResultContent] = React.useState('');
    const sortedItems = React.useMemo(() => {
        let sortableItems = [...currentItems];
        if (sortConfig !== null) {
            sortableItems.sort((a, b) => {
                if (a.type === 'folder' && b.type !== 'folder') return -1;
                if (a.type !== 'folder' && b.type === 'folder') return 1;
                if (a[sortConfig.key] < b[sortConfig.key]) { return sortConfig.direction === 'ascending' ? -1 : 1; }
                if (a[sortConfig.key] > b[sortConfig.key]) { return sortConfig.direction === 'ascending' ? 1 : -1; }
                return 0;
            });
        }
        return sortableItems;
    }, [currentItems, sortConfig]);
    const requestSort = (key) => {
        let direction = 'ascending';
        if (sortConfig.key === key && sortConfig.direction === 'ascending') {
            direction = 'descending';
        }
        setSortConfig({ key, direction });
    };

    const connectToTeam = (team) => {
        if (stompClientsRef.current.has(team.id)) return;

        const socket = new SockJS('/ws');
        const stompClient = Stomp.over(socket);
        stompClient.debug = null; // 콘솔 로그 비활성화

        stompClient.connect({}, (frame) => {
            console.log(`Connected to team: ${team.name} (${team.id})`);
            stompClientsRef.current.set(team.id, stompClient);

            stompClient.subscribe(`/topic/team/${team.id}`, (message) => {
                const parsedMessage = JSON.parse(message.body);

                if (team.id === selectedTeamContextRef.current?.id) {
                    setChatMessages(prev => [...prev, parsedMessage]);
                }

                if (parsedMessage.sender !== user.username) {
                    if (team.id !== selectedTeamContextRef.current?.id || activeCollabTabRef.current !== 'chat') {
                        setUnreadChats(prev => new Set(prev).add(team.id));
                    }
                }
            });
        }, (error) => {
            console.error(`Connection error for team ${team.id}:`, error);
        });
    };

    React.useEffect(() => {
        API.getFolders()
            .then(folders => {
                setPersonalFolders(folders);
                if (folders.length > 0) {
                    handlePersonalFolderSelect(folders[0]);
                }
            })
            .catch(err => console.error("루트 폴더 로딩 실패:", err));

        API.getMyTeams()
            .then(teams => {
                setMyTeams(teams);
                teams.forEach(team => connectToTeam(team));
            })
            .catch(err => console.error("내 팀 목록 로딩 실패:", err));

        return () => {
            console.log("Disconnecting all stomp clients...");
            stompClientsRef.current.forEach(client => client.disconnect());
            stompClientsRef.current.clear();
        };
    }, []);

    const handlePersonalFolderSelect = (folder) => {
        if (!folder || !folder.id) return;
        setSearchResults(null);
        setSearchQuery("");
        API.getFolderContents(folder.id)
            .then(content => {
                setCurrentItems(content.items);
                setBreadcrumbPath(content.path);
                setSelectedItems([]);
                setChatMessages([]);
                setEditingFile(null);
                setViewContext({ type: 'personal', id: folder.id, name: folder.name });
                setSelectedTeamContext(null);
                setCurrentUserPermissions({ canEdit: true, canDelete: true, canInvite: true, isTeamLeader: true });
            })
            .catch(err =>  console.error(" 폴더 내용 로딩 실패 :", err));
    };
    const handleTeamSubFolderSelect = (folder) => {
        if (!folder || !folder.id) return;
        setSearchResults(null);
        setSearchQuery("");
        API.getFolderContents(folder.id)
            .then(content => {
                setCurrentItems(content.items);
                setBreadcrumbPath(content.path);
                setSelectedItems([]);
                setViewContext(prev => ({ ...prev, id: folder.id, name: folder.name }));
            })
            .catch(err =>  console.error(" 폴더 내용 로딩 실패 :", err));
    };
    const handleTeamSelect = (team) => {
        if (!team || !team.id) return;
        setEditingFile(null);
        setSearchResults(null);
        setSearchQuery("");

        setActiveCollabTab('members');

        API.getChatHistory(team.id)
            .then(history => {
                setChatMessages(history);
            })
            .catch(err => console.error(err.message));

        API.getTeamRootItems(team.id)
            .then(content => {
                setCurrentItems(content.items);
                setBreadcrumbPath(content.path);
                const lastPathElement = content.path[content.path.length - 1];
                setViewContext({ type: 'team', id: lastPathElement.id, name: lastPathElement.name });
                setSelectedItems([]);
            })
            .catch(err =>  console.error(" 팀 폴더 내용 로딩 실패 :", err));

        API.getTeamMembers(team.id)
            .then(members => {
                setSelectedTeamContext({ ...team, members });
                const myMembership = members.find(m => m.username === user.username);
                if (myMembership) {
                    setCurrentUserPermissions({
                        canEdit: myMembership.canEdit,
                        canDelete: myMembership.canDelete,
                        canInvite: myMembership.canInvite,
                        isTeamLeader: myMembership.teamLeader,
                    });
                }
            })
            .catch(err => {
                console.error(" 팀 멤버 로딩 실패 :", err);
                setSelectedTeamContext({ ...team, members: [] });
            });
    };
    const refreshCurrentView = (showAlert = false) => {
        const refreshAction = viewContext.type === 'team'
            ? () => {
                const isRoot = viewContext.id === selectedTeamContext.id;
                if (isRoot) handleTeamSelect(selectedTeamContext);
                else handleTeamSubFolderSelect({ id: viewContext.id, name: viewContext.name });
            }
            : () => handlePersonalFolderSelect({ id: viewContext.id, name: viewContext.name });
        refreshAction();
        if (showAlert) {
            alert(" 현재 목록을 새로고침했습니다 .");
        }
    };

    const handleCollabTabSelect = (tabName, teamId) => {
        setActiveCollabTab(tabName);
        if (tabName === 'chat') {
            setUnreadChats(prev => {
                const newSet = new Set(prev);
                newSet.delete(teamId);
                return newSet;
            });
        }
    };

    const handleFileUpload = (file) => {
        if (!viewContext.id) return alert(" 파일을 업로드할 폴더를 선택해주세요 .");
        const options = { file };
        if (viewContext.type === 'team') {
            options.teamId = selectedTeamContext.id;
            if (viewContext.id !== selectedTeamContext.id) {
                options.folderId = viewContext.id;
            }
        } else {
            options.folderId = viewContext.id;
        }
        API.uploadFile(options)
            .then((fileInfo) => {
                alert(` 파일  '${fileInfo.name}' 이(가)  성공적으로 업로드되었습니다 .`);
                refreshCurrentView();
            })
            .catch(err => alert(err.message));
    };
    const handleCreateFolder = (folderName) => {
        if (!viewContext.id) return alert(" 폴더를 생성할 상위 폴더를 선택해주세요 .");
        const options = { folderName };
        if (viewContext.type === 'team') {
            options.teamId = selectedTeamContext.id;
            if (viewContext.id !== selectedTeamContext.id) {
                options.parentFolderId = viewContext.id;
            }
        } else {
            options.parentFolderId = viewContext.id;
        }
        API.createFolder(options)
            .then(() => {
                alert(" 폴더가 성공적으로 생성되었습니다 .");
                refreshCurrentView();
            })
            .catch(err => alert(err.message));
    };
    const handleItemSelect = (uniqueKey) => { setSelectedItems(prev => prev.includes(uniqueKey) ? prev.filter(key => key !== uniqueKey) : [...prev, uniqueKey]); };
    const handleSelectAll = () => {
        const allSelected = sortedItems.length > 0 && selectedItems.length === sortedItems.length;
        if (allSelected) {
            setSelectedItems([]);
        } else {
            setSelectedItems(sortedItems.map(item => item.uniqueKey));
        }
    };
    const handleDelete = () => {
        if (selectedItems.length === 0) return;
        if (viewContext.type === 'team' && !currentUserPermissions.canDelete) {
            alert(' 삭제 권한이 없습니다.');
            return;
        }
        if (confirm(` 선택한  ${selectedItems.length} 개의 항목을 정말로 삭제하시겠습니까 ?\n( 폴더는 휴지통 없이 영구 삭제됩니다 .)`)) {
            const filesToDelete = [];
            const foldersToDelete = [];
            selectedItems.forEach(key => {
                const [type, id] = key.split('-');
                if (type === 'file') {
                    filesToDelete.push(parseInt(id));
                } else if (type === 'folder') {
                    foldersToDelete.push(parseInt(id));
                }
            });
            const deletePromises = [
                ...filesToDelete.map(id =>  API.deleteFile(id)),
                ...foldersToDelete.map(id =>  API.deleteFolder(id))
            ];
            Promise.all(deletePromises)
                .then(() => {
                    alert(" 선택한 항목이 삭제되었습니다 .");
                    refreshCurrentView();
                })
                .catch(err => {
                    alert(" 삭제 중 오류가 발생했습니다 : " + err.message);
                    refreshCurrentView();
                });
        }
    };
    const handleRename = () => { if (selectedItems.length !== 1) return; const item = currentItems.find(it => it.uniqueKey === selectedItems[0]); if (item) { setItemToRename(item); setIsRenameModalOpen(true); } };
    const handleRenameSubmit = (newName) => { if (!itemToRename) return; const renamePromise = itemToRename.type === 'folder' ?  API.renameFolder(itemToRename.id, newName) :  API.renameFile(itemToRename.id, newName); renamePromise.then(() => { setCurrentItems(prevItems => prevItems.map(item => item.uniqueKey === itemToRename.uniqueKey ? { ...item, name: newName } : item )); setIsRenameModalOpen(false); setSelectedItems([]); }).catch(err => { alert(err.message); setIsRenameModalOpen(false); }); };
    const handleDownload = () => { if (selectedItems.length !== 1) return; const selectedKey = selectedItems[0]; const item = currentItems.find(it => it.uniqueKey === selectedKey); if (item.type === 'folder') { alert(" 폴더는 다운로드할 수 없습니다 ."); return; }  API.downloadFile(item.id, item.name).catch(err => alert(err.message)); };
    const handleMove = () => { if (selectedItems.length === 0) return; setIsMoveModalOpen(true); };
    const handleMoveSubmit = (destinationFolderId) => { if (!destinationFolderId) return alert(" 이동할 폴더를 선택해주세요 .");  API.moveItems(selectedItems, destinationFolderId).then(() => { alert(" 항목을 이동했습니다 ."); setIsMoveModalOpen(false); refreshCurrentView(); }).catch(err => alert(err.message)); };
    const handleCopy = () => { if (selectedItems.length === 0) return; setIsCopyModalOpen(true); };
    const handleCopySubmit = (destinationFolderId) => { if (!destinationFolderId) return alert(" 복사할 폴더를 선택해주세요 .");  API.copyItems(selectedItems, destinationFolderId).then(() => { alert(" 파일을 복사했습니다 ."); setIsCopyModalOpen(false); }).catch(err => alert(err.message)); };
    const handleShare = () => { if (selectedItems.length !== 1) return alert(" 하나의 항목만 선택해주세요 ."); const item = currentItems.find(it => it.uniqueKey === selectedItems[0]); if (!item || item.type === 'folder') return alert(" 파일만 공유할 수 있습니다 ."); setFileToShare(item); setIsShareModalOpen(true); };
    const handleCreateTeam = (teamName) => {  API.createTeam(teamName).then(() => { alert(" 팀이 성공적으로 생성되었습니다 .");  API.getMyTeams().then(setMyTeams); }).catch(err => alert(err.message)); };
    const handleInviteMember = (username) => {
        if (!selectedTeamContext) return alert(" 팀을 먼저 선택해주세요 .");
        API.inviteTeamMember(selectedTeamContext.id, username)
            .then(message => {
                alert(message);
            })
            .catch(err => alert(err.message));
    };
    const handleReloadTeamMembers = () => {
        if (!selectedTeamContext) return;
        API.getTeamMembers(selectedTeamContext.id)
            .then(members => {
                setSelectedTeamContext(prev => ({ ...prev, members }));
                alert(" 팀원 목록을 새로고침했습니다 .");
            })
            .catch(err => alert(" 팀원 목록을 새로고침하는 중 오류가 발생했습니다 : " + err.message));
    };
    const handleRemoveMember = (memberId) => { if (!selectedTeamContext) return; if (confirm(" 정말로 이 팀원을 추방하시겠습니까 ?")) {  API.removeMember(selectedTeamContext.id, memberId).then(() => { alert(" 팀원이 삭제되었습니다 ."); handleTeamSelect(selectedTeamContext); }).catch(err => alert(err.message)); } };
    const handleEditPermissions = (member) => { setMemberToEdit(member); setIsPermissionsModalOpen(true); };
    const handlePermissionsSubmit = (permissions) => { if (!selectedTeamContext || !memberToEdit) return;  API.updateMemberPermissions(selectedTeamContext.id, memberToEdit.memberId, permissions).then(() => { alert(" 팀원 권한이 수정되었습니다 ."); handleTeamSelect(selectedTeamContext); }).catch(err => alert(err.message)); };
    const handleDeleteOrLeaveTeam = () => {
        if (!selectedTeamContext) return;
        const isLeader = currentUserPermissions.isTeamLeader;
        const teamName = selectedTeamContext.name;
        if (isLeader) {
            const confirmation = prompt(` 팀을 영구적으로 삭제하려면 팀 이름  '${teamName}' 을 ( 를 )  정확하게 입력하세요 .\n 경고 :  이 작업은 되돌릴 수 없으며 ,  모든 파일과 폴더가 삭제됩니다 .`);
            if (confirmation === teamName) {
                API.deleteTeam(selectedTeamContext.id)
                    .then(message => {
                        alert(message);
                        window.location.reload();
                    })
                    .catch(err => alert(err.message));
            } else if (confirmation !== null) {
                alert(" 팀 이름이 일치하지 않아 삭제가 취소되었습니다 .");
            }
        } else {
            if (confirm(` 정말로  '${teamName}'  팀에서 나가시겠습니까 ?`)) {
                API.leaveTeam(selectedTeamContext.id)
                    .then(message => {
                        alert(message);
                        window.location.reload();
                    })
                    .catch(err => alert(err.message));
            }
        }
    };
    const handleClearChatHistory = () => {
        if (selectedTeamContext && confirm(" 정말로 모든 대화 내용을 삭제하시겠습니까 ?\n 이 작업은 되돌릴 수 없습니다 .")) {
            API.clearChatHistory(selectedTeamContext.id)
                .then(() => {
                    setChatMessages([]);
                    alert(" 대화 내용이 모두 삭제되었습니다.");
                })
                .catch(err => alert(err.message));
        }
    };
    const reloadMyTeams = () => {
        API.getMyTeams()
            .then(setMyTeams)
            .catch(err =>  console.error(" 내 팀 목록 새로고침 실패 :", err));
    };
    const handleDeleteAccount = () => {
        if (confirm(" 정말로 회원 탈퇴를 하시겠습니까? \n 모든 개인 데이터가 영구적으로 삭제되며, 이 작업은 되돌릴 수 없습니다 .")) {
            API.deleteAccount()
                .then(message => {
                    alert(message);
                    localStorage.removeItem("token");
                    localStorage.removeItem("username");
                    window.location.reload();
                })
                .catch(err => alert(err.message));
        }
    };
    const handleReloadMyTeams = () => {
        API.getMyTeams()
            .then(setMyTeams)
            .then(() => alert(" 팀 목록을 새로고침했습니다."))
            .catch(err => alert(" 팀 목록을 새로고침하는 중 오류가 발생했습니다 : " + err.message));
    };
    const handleSearch = () => {
        if (!searchQuery.trim()) {
            setSearchResults(null);
            refreshCurrentView();
            return;
        }
        const options = {
            query: searchQuery,
            teamId: viewContext.type === 'team' ? selectedTeamContext.id : null
        };
        API.searchFiles(options)
            .then(results => {
                setSearchResults(results);
            })
            .catch(err => alert(err.message));
    };
    const handleClearSearch = () => {
        setSearchQuery("");
        setSearchResults(null);
        refreshCurrentView();
    };
    const handleDelegateLeadership = (member) => { if (!selectedTeamContext) return; const confirmation = confirm(` 정말로  ${member.name} 님을 새로운 팀장으로 임명하시겠습니까 ?\n 이 작업은 되돌릴 수 없으며,  현재 팀장 권한을 잃게 됩니다 .`); if (confirmation) {  API.delegateLeadership(selectedTeamContext.id, member.memberId).then(() => { alert(" 팀장이 위임되었습니다 .  페이지를 새로고침합니다 .");  window.location.reload(); }).catch(err => alert(err.message)); } };

    const handleSendMessage = (message) => {
        if (viewContext.type === 'team') {
            const teamId = selectedTeamContext.id;
            const stompClient = stompClientsRef.current.get(teamId);
            if (stompClient && stompClient.connected) {
                stompClient.send(`/app/chat.sendMessage/${teamId}`, {}, JSON.stringify(message));
            }
        }
    };

    const handleOpenFile = (file) => { setFileToPreview(file); setIsPreviewModalOpen(true); };
    const handleRequestEdit = (file) => { setIsPreviewModalOpen(false);  API.getFileContent(file.id).then(content => { setEditingFile({ ...file, content }); }).catch(err => alert(err.message)); };
    const handleEditorContentChange = (newContent) => { setEditingFile(prev => ({ ...prev, content: newContent })); if (viewContext.type === 'team' && editingFile) {  Realtime.sendEditorChange(selectedTeamContext.id, { fileId: editingFile.id, content: newContent, editor: user.username }); } };
    const handleSaveFile = () => { if (!editingFile) return;  API.saveFileContent(editingFile.id, editingFile.content).then(message => { alert(message); setEditingFile(null); }).catch(err => alert(err.message)); };
    const handleShowHistory = () => { if (selectedItems.length !== 1) { alert(" 하나의 파일만 선택해주세요."); return; } const item = currentItems.find(it => it.uniqueKey === selectedItems[0]); if (!item || item.type === 'folder') { alert(" 파일의 버전 기록만 볼 수 있습니다 ."); return; } setFileForHistory(item); setIsHistoryModalOpen(true); };
    const handleRestoreVersion = (versionId) => { if (!fileForHistory) return;  API.restoreVersion(fileForHistory.id, versionId).then(message => { alert(message + "\n\n 파일을 다시 열면 복원된 내용을 확인할 수 있습니다 ."); setIsHistoryModalOpen(false); if (editingFile) { setEditingFile(null); } }).catch(err => alert(err.message)); };
    const handleSummarize = () => { if (!editingFile) return; setAiModalTitle(" 요약 결과 ( 모의 형태 )"); setAiResultContent(""); setIsAiModalOpen(true);  API.summarizeFile(editingFile.id).then(result => setAiResultContent(result)).catch(err => setAiResultContent(" 오류 발생 : " + err.message)); };
    const handleTranslate = () => { if (!editingFile) return; setAiModalTitle(" 영문 번역 결과 "); setAiResultContent(""); setIsAiModalOpen(true);  API.translateFile(editingFile.id, 'en').then(result => setAiResultContent(result)).catch(err => setAiResultContent(" 오류 발생 : " + err.message)); };
    const handleTranslateKo = () => {
        if (!editingFile) return;
        setAiModalTitle(" 한글 번역 결과 ");
        setAiResultContent("");
        setIsAiModalOpen(true);
        API.translateFile(editingFile.id, 'ko')
            .then(result => setAiResultContent(result))
            .catch(err => setAiResultContent(" 오류 발생 : " + err.message));
    };
    const handleBreadcrumbNavigate = (folderId) => {
        if (selectedTeamContext && folderId === selectedTeamContext.id) {
            const team = myTeams.find(t => t.id === folderId);
            if (team) {
                handleTeamSelect(team);
            }
        } else {
            if (viewContext.type === 'team') {
                handleTeamSubFolderSelect({id: folderId});
            } else {
                handlePersonalFolderSelect({ id: folderId });
            }
        }
    };

    return (
        <div className="h-screen bg-gray-50 flex flex-col">
            <Header user={user} reloadMyTeams={reloadMyTeams} onDeleteAccount={handleDeleteAccount} />

            <div className="flex-grow grid grid-cols-12 gap-6 p-6 overflow-hidden">

                <div className="col-span-3 overflow-y-auto">
                    <Sidebar
                        personalFolders={personalFolders}
                        myTeams={myTeams}
                        onFolderSelect={handlePersonalFolderSelect}
                        onTeamSelect={handleTeamSelect}
                        onNewTeamClick={() => setIsNewTeamModalOpen(true)}
                        onReloadMyTeams={handleReloadMyTeams}
                        activeContext={viewContext}
                        activePersonalRootId={
                            viewContext.type === 'personal' && breadcrumbPath.length ? breadcrumbPath[0].id : null
                        }
                        selectedTeamContext={selectedTeamContext}
                        unreadChats={unreadChats}
                    />
                </div>

                <div className="col-span-6 flex flex-col gap-4 overflow-hidden">
                    {editingFile ? (
                        <EditorView
                            file={editingFile}
                            onContentChange={handleEditorContentChange}
                            onClose={() => setEditingFile(null)}
                            onSave={handleSaveFile}
                            onSummarize={handleSummarize}
                            onTranslate={handleTranslate}
                            onTranslateKo={handleTranslateKo}
                        />
                    ) : (
                        <>
                            <div className="flex-shrink-0">
                                <Toolbar
                                    selectedCount={selectedItems.length}
                                    permissions={currentUserPermissions}
                                    onDelete={handleDelete}
                                    onRename={handleRename}
                                    onDownload={handleDownload}
                                    onMove={handleMove}
                                    onCopy={handleCopy}
                                    onShare={handleShare}
                                    onShowHistory={handleShowHistory}
                                />
                            </div>
                            <div className="flex-grow min-h-0 overflow-y-auto">
                                <MainContent
                                    items={sortedItems}
                                    viewContext={viewContext}
                                    permissions={currentUserPermissions}
                                    onFolderSelect={viewContext.type === 'team' ? handleTeamSubFolderSelect : handlePersonalFolderSelect}
                                    onFileUpload={handleFileUpload}
                                    onNewFolderClick={() => setIsNewFolderModalOpen(true)}
                                    onRefresh={() => refreshCurrentView(true)}
                                    selectedItems={selectedItems}
                                    onItemSelect={handleItemSelect}
                                    onSelectAll={handleSelectAll}
                                    onOpenFile={handleOpenFile}
                                    requestSort={requestSort}
                                    sortConfig={sortConfig}
                                    breadcrumbPath={breadcrumbPath}
                                    onBreadcrumbNavigate={handleBreadcrumbNavigate}
                                    onSearch={handleSearch}
                                    searchQuery={searchQuery}
                                    setSearchQuery={setSearchQuery}
                                    searchResults={searchResults}
                                    onClearSearch={handleClearSearch}
                                />
                            </div>
                        </>
                    )}
                </div>

                <div className="col-span-3 overflow-hidden">
                    {selectedTeamContext ? (
                        <CollaborationPanel
                            user={user}
                            teamContext={selectedTeamContext}
                            permissions={currentUserPermissions}
                            onInviteClick={() => setIsInviteModalOpen(true)}
                            onRemoveMember={handleRemoveMember}
                            onEditPermissions={handleEditPermissions}
                            onDelegateLeadership={handleDelegateLeadership}
                            onDeleteOrLeaveTeam={handleDeleteOrLeaveTeam}
                            onClearChatHistory={handleClearChatHistory}
                            onReloadMembers={handleReloadTeamMembers}
                            chatMessages={chatMessages}
                            onSendMessage={handleSendMessage}
                            onFileUploadSuccess={refreshCurrentView}
                            unreadChats={unreadChats}
                            activeTab={activeCollabTab}
                            onTabSelect={handleCollabTabSelect}
                        />
                    ) : (
                        <InfoPanel />
                    )}
                </div>
            </div>

            <NewFolderModal
                isOpen={isNewFolderModalOpen}
                onClose={() => setIsNewFolderModalOpen(false)}
                onAction={handleCreateFolder}
            />
            <RenameModal
                isOpen={isRenameModalOpen}
                onClose={() => setIsRenameModalOpen(false)}
                onAction={handleRenameSubmit}
                currentName={itemToRename?.name || ""}
            />
            <MoveModal
                isOpen={isMoveModalOpen}
                onClose={() => setIsMoveModalOpen(false)}
                onAction={handleMoveSubmit}
                title=" 이동할 위치 선택 "
                buttonText=" 여기로 이동 "
                teamContext={selectedTeamContext}
            />
            <MoveModal
                isOpen={isCopyModalOpen}
                onClose={() => setIsCopyModalOpen(false)}
                onAction={handleCopySubmit}
                title=" 복사할 위치 선택 "
                buttonText=" 여기에 복사 "
                teamContext={selectedTeamContext}
            />
            <ShareModal
                isOpen={isShareModalOpen}
                onClose={() => setIsShareModalOpen(false)}
                fileToShare={fileToShare}
            />
            <NewTeamModal
                isOpen={isNewTeamModalOpen}
                onClose={() => setIsNewTeamModalOpen(false)}
                onAction={handleCreateTeam}
            />
            <InviteMemberModal
                isOpen={isInviteModalOpen}
                onClose={() => setIsInviteModalOpen(false)}
                onAction={handleInviteMember}
            />
            <EditPermissionsModal
                isOpen={isPermissionsModalOpen}
                onClose={() => setIsPermissionsModalOpen(false)}
                onAction={handlePermissionsSubmit}
                member={memberToEdit}
            />
            <VersionHistoryModal
                isOpen={isHistoryModalOpen}
                onClose={() => setIsHistoryModalOpen(false)}
                file={fileForHistory}
                onRestore={handleRestoreVersion}
            />
            <PreviewModal
                isOpen={isPreviewModalOpen}
                onClose={() => setIsPreviewModalOpen(false)}
                file={fileToPreview}
                onEdit={handleRequestEdit}
            />
            <AiResultModal
                isOpen={isAiModalOpen}
                onClose={() => setIsAiModalOpen(false)}
                title={aiModalTitle}
                content={aiResultContent}
                isLoading={!aiResultContent}
            />
        </div>
    );
};