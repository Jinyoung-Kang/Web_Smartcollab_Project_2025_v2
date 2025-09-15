const Icon = ({ name, className }) => {
    const [isLucideReady, setIsLucideReady] = React.useState(!!window.lucide);

    React.useEffect(() => {
        if (!isLucideReady) {
            const intervalId = setInterval(() => {
                if (window.lucide) {
                    setIsLucideReady(true);
                    clearInterval(intervalId);
                }
            }, 50);
            return () => clearInterval(intervalId);
        }
    }, [isLucideReady]);

    if (!isLucideReady) {
        return <span style={{ display: 'inline-block', width: '1em', height: '1em' }} />;
    }

    const iconNode = window.lucide.icons[name];
    if (!iconNode) {
        console.warn(`[Icon] Icon not found: ${name}`);
        return <span className="text-red-500 font-bold">?</span>;
    }
    const innerHtml = iconNode[2].map(([tag, attrs]) => {
        const attrString = Object.entries(attrs).map(([key, value]) => `${key}="${value}"`).join(' ');
        return `<${tag} ${attrString}></${tag}>`;
    }).join('');

    return React.createElement('svg', {
        xmlns: "http://www.w3.org/2000/svg",
        width: "24", height: "24", viewBox: "0 0 24 24",
        fill: "none", stroke: "currentColor", strokeWidth: "2",
        strokeLinecap: "round", strokeLinejoin: "round",
        className: className,
        dangerouslySetInnerHTML: { __html: innerHtml },
    });
};

const NotificationPing = () => (
    <span className="absolute top-1 right-1 flex h-3 w-3">
        <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-red-400 opacity-75"></span>
        <span className="relative inline-flex rounded-full h-3 w-3 bg-red-500"></span>
    </span>
);

const EditorView = ({ file, onContentChange, onClose, onSave, onSummarize, onTranslate, onTranslateKo }) => {
    return (
        <div className="bg-white rounded-lg shadow h-full flex flex-col">
            <div className="p-4 border-b flex justify-between items-center">
                <h3 className="font-bold text-lg">{file.name}</h3>
                <div className="flex items-center space-x-2">
                    <button
                        onClick={onSummarize}
                        className="px-3 py-2 bg-green-500 text-white text-xs rounded-lg hover:bg-green-600">
                        요약하기
                    </button>

                    <button
                        onClick={onTranslate}
                        className="px-3 py-2 bg-purple-500 text-white text-xs rounded-lg hover:bg-purple-600">
                        영어로 번역
                    </button>

                    <button
                        onClick={onTranslateKo}
                        className="px-3 py-2 bg-indigo-500 text-white text-xs rounded-lg hover:bg-indigo-600">
                        한글로 번역
                    </button>

                    <div className="border-l h-6 mx-2"></div>

                    <button
                        onClick={onSave}
                        className="px-3 py-2 bg-blue-500 text-white text-xs rounded-lg hover:bg-blue-600">
                        저장
                    </button>
                    <button
                        onClick={onClose}
                        className="px-3 py-2 bg-gray-200 text-gray-700 text-xs rounded-lg hover:bg-gray-300">
                        닫기
                    </button>
                </div>
            </div>

            <textarea
                className="flex-1 p-4 outline-none"
                value={file.content || ""}
                onChange={(e) => onContentChange(e.target.value)}
            />
        </div>
    );
};

const Toolbar = ({ selectedCount, permissions, onDelete, onRename, onDownload, onMove, onCopy, onShare, onShowHistory }) => {
    const baseStyle = "px-3 py-1.5 text-sm rounded-md";
    const disabledStyle = "bg-gray-200 text-gray-500 cursor-not-allowed";
    const enabledStyle = "bg-white border text-gray-700 hover:bg-gray-100";

    return (
        <div className="flex items-center space-x-2 p-2 bg-gray-100 rounded-lg">
            {permissions.canEdit && (
                <button onClick={onRename} disabled={selectedCount !== 1} className={`${baseStyle} ${selectedCount === 1 ? enabledStyle : disabledStyle}`}>
                    이름 변경
                </button>
            )}

            <button onClick={onDelete} disabled={selectedCount === 0} className={`${baseStyle} ${selectedCount > 0 ? enabledStyle : disabledStyle}`}>
                삭제
            </button>

            <button onClick={onDownload} disabled={selectedCount !== 1} className={`${baseStyle} ${selectedCount === 1 ? enabledStyle : disabledStyle}`}>
                다운로드
            </button>
            {permissions.canEdit && (
                <>
                    <button onClick={onMove} disabled={selectedCount === 0} className={`${baseStyle} ${selectedCount > 0 ? enabledStyle : disabledStyle}`}>
                        이동
                    </button>
                    <button onClick={onCopy} disabled={selectedCount === 0} className={`${baseStyle} ${selectedCount > 0 ? enabledStyle : disabledStyle}`}>
                        복사
                    </button>
                </>
            )}
            <button onClick={onShare} disabled={selectedCount !== 1} className={`${baseStyle} ${selectedCount === 1 ? enabledStyle : disabledStyle}`}>
                공유
            </button>
            <button onClick={onShowHistory} disabled={selectedCount !== 1} className={`${baseStyle} ${selectedCount === 1 ? enabledStyle : disabledStyle}`}>
                버전 기록
            </button>
        </div>
    );
};

const Header = ({ user, reloadMyTeams, onDeleteAccount }) => {
    const [notifications, setNotifications] = React.useState([]);
    const [isNotificationOpen, setIsNotificationOpen] = React.useState(false);

    const fetchNotifications = () => {
        API.getNotifications()
            .then(setNotifications)
            .catch(err => console.error(err.message));
    };

    React.useEffect(() => {
        fetchNotifications();
        const interval = setInterval(fetchNotifications, 10000); // 10초마다 알림 새로고침
        return () => clearInterval(interval);
    }, []);

    const handleNotificationClick = (notification) => {
        API.markNotificationAsRead(notification.id).then(() => {
            setNotifications(prev => prev.filter(n => n.id !== notification.id));
        }).catch(err => alert(err.message));
    };

    const handleAccept = (notification) => {
        API.acceptInvitation(notification.invitationId)
            .then(message => {
                alert(message);
                handleNotificationClick(notification);
                reloadMyTeams();
            })
            .catch(err => alert(err.message));
    };

    const handleReject = (notification) => {
        API.rejectInvitation(notification.invitationId)
            .then(message => {
                alert(message);
                handleNotificationClick(notification);
            })
            .catch(err => alert(err.message));
    };

    const handleDelete = (notificationId) => {
        API.deleteNotification(notificationId)
            .then(() => {
                setNotifications(prev => prev.filter(n => n.id !== notificationId));
            })
            .catch(err => alert(err.message));
    };

    const handleDeleteAll = () => {
        if (notifications.length > 0 && confirm("모든 알림을 삭제하시겠습니까?")) {
            API.deleteAllNotifications()
                .then(() => {
                    setNotifications([]);
                })
                .catch(err => alert(err.message));
        }
    };

    return (
        <header className="flex items-center justify-between p-4 bg-white border-b">
            <h1 className="text-xl font-bold text-blue-600">SmartCollab</h1>
            <div className="flex items-center space-x-4">
                <div className="relative">
                    <button onClick={() => setIsNotificationOpen(prev => !prev)} className="relative px-3 py-1 text-sm text-yellow-500 rounded hover:bg-gray-100">
                        알림
                        {notifications.length > 0 && (
                            <span className="absolute -top-1 -right-1 flex h-3 w-3">
                                <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-red-400 opacity-75"></span>
                                <span className="relative inline-flex rounded-full h-3 w-3 bg-red-500"></span>
                            </span>
                        )}
                    </button>
                    {isNotificationOpen && (
                        <div className="absolute right-0 mt-2 w-80 bg-white rounded-md shadow-lg z-20">
                            <div className="p-3 border-b flex justify-between items-center">
                                <span className="font-semibold">알림</span>
                                <button onClick={handleDeleteAll} className="text-xs text-gray-500 hover:text-red-500">전체 삭제</button>
                            </div>
                            <ul className="py-1 max-h-96 overflow-y-auto">
                                {notifications.length > 0 ? notifications.map(n => (
                                    <li key={n.id} className="px-3 py-2 text-sm text-gray-700 hover:bg-gray-50">
                                        <div className="flex justify-between items-start">
                                            <div className="pr-2">
                                                <p onClick={() => n.type !== 'TEAM_INVITE' && handleNotificationClick(n)}>{n.content}</p>
                                                {n.type === 'TEAM_INVITE' && (
                                                    <div className="mt-2 flex justify-end space-x-2">
                                                        <button onClick={() => handleReject(n)} className="px-2 py-1 text-xs bg-gray-200 rounded hover:bg-gray-300">거절</button>
                                                        <button onClick={() => handleAccept(n)} className="px-2 py-1 text-xs bg-blue-500 text-white rounded hover:bg-blue-600">수락</button>
                                                    </div>
                                                )}
                                            </div>

                                            <button onClick={() => handleDelete(n.id)} className="text-gray-400 hover:text-red-500 p-1">
                                                <img src="/js/images/delete_icon.png" alt="삭제" className="w-4 h-4" />
                                            </button>
                                        </div>
                                    </li>
                                )) : (
                                    <li className="px-3 py-4 text-center text-sm text-gray-500">새로운 알림이 없습니다.</li>
                                )}
                            </ul>
                        </div>
                    )}
                </div>
                <div className="flex items-center space-x-2">
                    <span className="font-semibold">{user.username} 님</span>
                </div>
                <button onClick={() => { localStorage.removeItem("token"); window.location.reload(); }} className="px-3 py-1 text-sm bg-gray-200 rounded hover:bg-gray-300">
                    로그아웃
                </button>
                <button onClick={onDeleteAccount} className="px-3 py-1 text-sm text-red-500 bg-gray-200 rounded hover:bg-red-100">
                    회원 탈퇴
                </button>
            </div>
        </header>
    );
};

const Sidebar = ({
                     personalFolders, myTeams,
                     onFolderSelect, onTeamSelect, onNewTeamClick, onReloadMyTeams,
                     activeContext, activePersonalRootId, selectedTeamContext,
                     unreadChats
                 }) => (
    <aside className="col-span-3 bg-white p-4 rounded-lg shadow">
        <h2 className="text-lg font-bold mb-4"> 내 드라이브 </h2>
        <nav className="space-y-2">
            {personalFolders.map(folder => (
                <a
                    href="#"
                    key={folder.id}
                    onClick={(e) => { e.preventDefault(); onFolderSelect(folder); }}
                    className={`flex items-center p-2 space-x-2 text-gray-700 rounded-md hover:bg-gray-100 ${
                        (activeContext?.type === 'personal' &&
                            ((activePersonalRootId && folder.id === activePersonalRootId) ||
                                (!activePersonalRootId && folder.id === activeContext?.id)))
                            ? 'bg-gray-100' : ''
                    }`}
                >
                    <img src="/js/images/storage_icon.png" alt="storage" className="w-5 h-5" />
                    <span>{folder.name}</span>
                </a>
            ))}
        </nav>
        <hr className="my-6" />
        <div className="flex justify-between items-center mb-4">
            <h2 className="text-lg font-bold"> 팀 스토리지 </h2>
            <div className="flex items-center space-x-2">
                <button onClick={onReloadMyTeams} title=" 팀 목록 새로고침 " className="p-1 rounded-full hover:bg-gray-200">
                    <img src="/js/images/refresh_icon.png" alt=" 새로고침 " className="w-4 h-4" />
                </button>
                <button onClick={onNewTeamClick} className="px-2 py-1 text-xs bg-blue-100 text-blue-700 rounded hover:bg-blue-200">
                    새 팀 생성
                </button>
            </div>
        </div>
        <div className="space-y-2">
            {myTeams.map(team => (
                <div key={team.id} className="relative">
                    <a
                        href="#"
                        onClick={(e) => { e.preventDefault(); onTeamSelect(team); }}
                        className={`flex items-center p-2 space-x-2 text-gray-700 rounded-md hover:bg-gray-100 ${
                            (activeContext?.type === 'team' && selectedTeamContext?.id === team.id)
                                ? 'bg-gray-100' : ''
                        }`}
                    >
                        <img src="/js/images/storage_icon.png" alt="team storage" className="w-5 h-5" />
                        <span>{team.name} ({team.memberCount} 명)</span>
                    </a>
                    {unreadChats.has(team.id) && <NotificationPing />}
                </div>
            ))}
        </div>
    </aside>
);

const MainContent = ({ items, viewContext, permissions, onFolderSelect, onFileUpload, onNewFolderClick, onRefresh, selectedItems, onItemSelect, onSelectAll, onOpenFile, requestSort, sortConfig, breadcrumbPath, onBreadcrumbNavigate, onSearch, searchQuery, setSearchQuery, searchResults, onClearSearch }) => {
    const fileInputRef = React.useRef(null);
    const handleUploadClick = () => { fileInputRef.current.click(); };
    const handleFileChange = (event) => {
        const file = event.target.files[0];
        if (file) onFileUpload(file);
        event.target.value = null;
    };

    const FileIcon = ({ type }) => {
        const iconSrc = type === 'folder'
            ? '/js/images/folder_icon.png'
            : '/js/images/file_icon.png';
        return <img src={iconSrc} alt={type} className="w-8 h-8" />;
    };

    const SortIcon = ({ direction }) => {
        if (!direction) return null;
        const classNames = `w-3 h-3 ml-1 transition-transform ${direction === 'descending' ? 'transform rotate-180' : ''}`;
        return (
            <img src="/js/images/sorting_icon.png" alt="sort" className={classNames} />
        );
    };

    const Breadcrumb = ({ path, onNavigate }) => {
        return (
            <nav className="flex items-center space-x-2 text-sm text-gray-500 mb-4">
                {path.map((folder, index) => (
                    <React.Fragment key={folder.id}>
                        {index > 0 && <img src="/js/images/right_icon.png" alt=">" className="w-4 h-4" />}
                        <button
                            onClick={() => onNavigate(folder.id)}
                            className="hover:underline hover:text-blue-600"
                        >
                            {folder.name}
                        </button>
                    </React.Fragment>
                ))}
            </nav>
        );
    };

    const handleSearchKeyDown = (e) => {
        if (e.key === 'Enter') {
            onSearch();
        }
    };

    return (
        <div className="bg-white p-6 rounded-lg shadow">
            <div className="flex justify-between items-center mb-4">
                <Breadcrumb path={breadcrumbPath} onNavigate={onBreadcrumbNavigate} />
                <div className="flex items-center space-x-2">
                    <input
                        type="text"
                        placeholder="파일 검색..."
                        value={searchQuery}
                        onChange={(e) => setSearchQuery(e.target.value)}
                        onKeyDown={handleSearchKeyDown}
                        className="border p-1.5 rounded-md text-sm"
                    />
                    <button onClick={onSearch} className="px-3 py-1.5 bg-blue-500 text-white text-sm rounded-md hover:bg-blue-600">검색</button>
                    {searchResults && (
                        <button onClick={onClearSearch} className="text-xs text-gray-500 hover:text-red-500">초기화</button>
                    )}
                </div>
            </div>
            <div className="flex justify-between items-center mb-6">
                <h2 className="text-2xl font-bold">{searchResults ? `'${searchQuery}' 검색 결과` : viewContext.name}</h2>
                <div className="flex items-center space-x-2">

                    {permissions.canEdit && !searchResults && (
                        <>
                            <button onClick={onRefresh} title="새로고침" className="p-2 rounded-full hover:bg-gray-200">
                                <img src="/js/images/refresh_icon.png" alt="새로고침" className="w-5 h-5" />
                            </button>

                            <input type="file" ref={fileInputRef} onChange={handleFileChange} style={{ display: 'none' }} />
                            <button onClick={handleUploadClick} className="px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700">
                                업로드
                            </button>
                            <button onClick={onNewFolderClick} className="px-4 py-2 bg-gray-200 rounded-lg hover:bg-gray-300">
                                새 폴더
                            </button>
                        </>
                    )}
                </div>
            </div>
            <div className="border-t">
                {searchResults ? (
                    <table className="w-full text-left">
                        <thead>
                        <tr className="border-b">
                            <th className="p-2 w-16"></th>
                            <th className="p-2">이름</th>
                            <th className="p-2">경로</th>
                            <th className="p-2">생성한 날짜</th>
                            <th className="p-2">생성자</th>
                        </tr>
                        </thead>
                        <tbody>
                        {searchResults.map(item => (
                            <tr key={item.uniqueKey} className="border-b hover:bg-gray-50 cursor-pointer" onDoubleClick={() => onOpenFile(item)}>
                                <td className="p-3"><FileIcon type={item.type} /></td>
                                <td className="p-3 font-semibold">{item.name}</td>
                                <td className="p-3 text-gray-500 text-sm">{item.path}</td>
                                <td className="p-3 text-gray-600">{new Date(item.modifiedAt).toLocaleString()}</td>
                                <td className="p-3 text-gray-600">{item.ownerName}</td>
                            </tr>
                        ))}
                        </tbody>
                    </table>
                ) : (
                    <table className="w-full text-left">
                        <thead>
                        <tr className="border-b">
                            <th className="p-3 w-8">
                                <input
                                    type="checkbox"
                                    checked={items.length > 0 && selectedItems.length === items.length}
                                    onChange={onSelectAll}
                                />
                            </th>
                            <th className="p-2">
                                <button onClick={() => requestSort('name')} className="group flex items-center font-semibold w-full px-2 py-1 rounded-md hover:bg-gray-100 transition-colors">
                                    이름
                                    <span className={sortConfig.key === 'name' ? '' : 'opacity-0 group-hover:opacity-100'}>
                                            <SortIcon direction={sortConfig.key === 'name' ? sortConfig.direction : 'ascending'} />
                                        </span>
                                </button>
                            </th>
                            <th className="p-2">
                                <button onClick={() => requestSort('modifiedAt')} className="group flex items-center font-semibold w-full px-2 py-1 rounded-md hover:bg-gray-100 transition-colors">
                                    생성한 날짜
                                    <span className={sortConfig.key === 'modifiedAt' ? '' : 'opacity-0 group-hover:opacity-100'}>
                                            <SortIcon direction={sortConfig.key === 'modifiedAt' ? sortConfig.direction : 'ascending'} />
                                        </span>
                                </button>
                            </th>
                            <th className="p-2">
                                <button onClick={() => requestSort('ownerName')} className="group flex items-center font-semibold w-full px-2 py-1 rounded-md hover:bg-gray-100 transition-colors">
                                    생성자
                                    <span className={sortConfig.key === 'ownerName' ? '' : 'opacity-0 group-hover:opacity-100'}>
                                            <SortIcon direction={sortConfig.key === 'ownerName' ? sortConfig.direction : 'ascending'} />
                                        </span>
                                </button>
                            </th>
                            <th className="p-2">
                                <button onClick={() => requestSort('size')} className="group flex items-center font-semibold w-full px-2 py-1 rounded-md hover:bg-gray-100 transition-colors">
                                    파일 크기
                                    <span className={sortConfig.key === 'size' ? '' : 'opacity-0 group-hover:opacity-100'}>
                                            <SortIcon direction={sortConfig.key === 'size' ? sortConfig.direction : 'ascending'} />
                                        </span>
                                </button>
                            </th>
                        </tr>
                        </thead>
                        <tbody>
                        {items.map(item => (
                            <tr key={item.uniqueKey}
                                className={`border-b hover:bg-gray-50 cursor-pointer`}
                                onDoubleClick={() => {
                                    if (item.type === 'folder') {
                                        onFolderSelect(item);
                                    } else {
                                        onOpenFile(item);
                                    }
                                }}>
                                <td className="p-3">
                                    <input
                                        type="checkbox"
                                        checked={selectedItems.includes(item.uniqueKey)}
                                        onChange={() => onItemSelect(item.uniqueKey)}
                                    />
                                </td>
                                <td className="p-3 flex items-center space-x-3">
                                    <FileIcon type={item.type} />
                                    <span>{item.name}</span>
                                </td>
                                <td className="p-3 text-gray-600">{new Date(item.modifiedAt).toLocaleString()}</td>
                                <td className="p-3 text-gray-600">{item.ownerName}</td>
                                <td className="p-3 text-gray-600">{item.size ? `${(item.size / 1024).toFixed(1)} KB` : '--'}</td>
                            </tr>
                        ))}
                        </tbody>
                    </table>
                )}
            </div>
        </div>
    );
};

const CollaborationPanel = ({
                                user, teamContext, permissions,
                                onInviteClick, onRemoveMember, onEditPermissions, onDelegateLeadership,
                                onDeleteOrLeaveTeam, onClearChatHistory, onReloadMembers,
                                chatMessages, onSendMessage,
                                unreadChats, activeTab, onTabSelect
                            }) => {
    const commonTabStyle = "px-4 py-2 text-sm font-medium relative";
    const activeTabStyle = "border-b-2 border-blue-500 text-blue-600";
    const inactiveTabStyle = "text-gray-500 hover:text-gray-700";
    const isCurrentUserLeader = user.username === teamContext.ownerUsername;
    return (
        <aside className="col-span-3 bg-white rounded-lg shadow flex flex-col h-full">
            <div className="border-b">
                <nav className="-mb-px flex space-x-4 px-4">
                    <button onClick={() => onTabSelect('members', teamContext.id)} className={`${commonTabStyle} ${activeTab === 'members' ? activeTabStyle : inactiveTabStyle}`}>
                        팀원 ({teamContext.members.length})
                    </button>
                    <button onClick={() => onTabSelect('chat', teamContext.id)} className={`${commonTabStyle} ${activeTab === 'chat' ? activeTabStyle : inactiveTabStyle}`}>
                        채팅
                        {unreadChats.has(teamContext.id) && <NotificationPing />}
                    </button>
                </nav>
            </div>
            {activeTab === 'members' && (
                <div className="p-4">
                    <div className="flex justify-between items-center mb-4">
                        <h3 className="font-bold"> 팀원 관리 </h3>
                        <div className="flex items-center space-x-2">
                            <button onClick={onReloadMembers} title=" 팀원 목록 새로고침 " className="p-1 rounded-full hover:bg-gray-200">
                                <img src="/js/images/refresh_icon.png" alt=" 새로고침 " className="w-4 h-4" />
                            </button>
                            {permissions.canInvite && (
                                <button onClick={onInviteClick} className="px-2 py-1 text-xs bg-gray-200 rounded hover:bg-gray-300">
                                    초대
                                </button>
                            )}
                            <button
                                onClick={onDeleteOrLeaveTeam}
                                className="px-2 py-1 text-xs text-white bg-red-500 rounded hover:bg-red-600"
                            >
                                {isCurrentUserLeader ? " 팀 삭제 " : " 팀 나가기 "}
                            </button>
                        </div>
                    </div>
                    <ul className="space-y-3 text-sm">
                        {teamContext.members.map(member => (
                            <li key={member.memberId} className="flex items-center justify-between group">
                                <div className="flex items-center space-x-2">
                                    <img src="/js/images/user_icon.png" alt="user" className="w-4 h-4" />
                                    <span>{member.name} ({member.username})</span>
                                </div>
                                <div className="flex items-center space-x-2">
                                    {member.teamLeader && <span className="px-2 py-0.5 text-xs font-semibold text-white bg-blue-500 rounded-full"> 팀장 </span>}
                                    {isCurrentUserLeader && !member.teamLeader && (
                                        <div className="flex items-center text-xs space-x-2">
                                            <button onClick={() => onDelegateLeadership(member)} className="text-gray-500 hover:text-yellow-600"> 위임 </button>
                                            <button onClick={() => onEditPermissions(member)} className="text-gray-500 hover:text-blue-600"> 권한 </button>
                                            <button onClick={() => onRemoveMember(member.memberId)} className="text-gray-500 hover:text-red-600"> 추방 </button>
                                        </div>
                                    )}
                                </div>
                            </li>
                        ))}
                    </ul>
                </div>
            )}
            {activeTab === 'chat' && (
                <div className="flex-grow min-h-0">
                    <ChatPanel
                        teamId={teamContext.id}
                        messages={chatMessages}
                        onSendMessage={onSendMessage}
                        username={user.username}
                        isCurrentUserLeader={isCurrentUserLeader}
                        onClearChatHistory={onClearChatHistory}
                    />
                </div>
            )}
        </aside>
    );
};

const ChatPanel = ({ teamId, messages, onSendMessage, onFileUploadSuccess, username, isCurrentUserLeader, onClearChatHistory }) => {
    const [newMessage, setNewMessage] = React.useState('');
    const messagesEndRef = React.useRef(null);
    const fileInputRef = React.useRef(null);

    const scrollToBottom = () => {
        messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
    };

    React.useEffect(() => {
        scrollToBottom();
    }, [messages]);

    const handleSendText = (e) => {
        e.preventDefault();
        if (newMessage.trim()) {
            onSendMessage({ sender: username, content: newMessage, type: 'CHAT' });
            setNewMessage('');
        }
    };

    const handleFileSelect = (e) => {
        const file = e.target.files[0];
        if (file) {
            API.uploadFile({ file: file, teamId: teamId })
                .then(fileInfo => {
                    onSendMessage({
                        sender: username,
                        content: fileInfo.name,
                        type: 'FILE_SHARE',
                        fileId: fileInfo.id,
                        fileType: fileInfo.name.split('.').pop(),
                        fileSize: fileInfo.size,
                    });

                })
                .catch(err => alert("파일 업로드 실패: " + err.message));
        }
        e.target.value = null;
    };

    const formatTime = (isoString) => {
        if (!isoString) return '';
        const date = new Date(isoString);
        let hours = date.getHours();
        let minutes = date.getMinutes();
        const ampm = hours >= 12 ? '오후' : '오전';
        hours = hours % 12;
        hours = hours ? hours : 12;
        minutes = minutes < 10 ? '0' + minutes : minutes;
        return `${ampm} ${hours}:${minutes}`;
    };


    const formatDate = (isoString) => {
        if (!isoString) return null;
        const date = new Date(isoString);
        return `${date.getFullYear()}년 ${date.getMonth() + 1}월 ${date.getDate()}일`;
    };

    let lastDate = null; // 마지막으로 표시된 날짜를 추적하기 위한 변수

    return (
        <div className="flex flex-col h-full">
            <div className="p-3 border-b flex justify-between items-center">
                <h3 className="font-bold text-sm">팀 채팅</h3>
                {isCurrentUserLeader && (
                    <button onClick={onClearChatHistory} className="px-2 py-1 text-xs bg-gray-200 rounded hover:bg-gray-300">
                        메시지 비우기
                    </button>
                )}
            </div>

            <div className="flex-grow min-h-0 overflow-y-auto p-3 space-y-4">
                {messages.map((msg, index) => {
                    if (msg.type === 'JOIN' || msg.type === 'LEAVE') {
                        return null; // 아무것도 렌더링하지 않음
                    }

                    const currentDate = formatDate(msg.createdAt);
                    let dateSeparator = null;

                    if (currentDate && currentDate !== lastDate) {
                        dateSeparator = (
                            <div key={`date-${index}`} className="text-center text-xs text-gray-500 my-4">
                                <span className="bg-gray-200 px-2 py-1 rounded-full">{currentDate}</span>
                            </div>
                        );
                        lastDate = currentDate;
                    }

                    const isMyMessage = msg.sender === username;

                    return (
                        <React.Fragment key={index}>
                            {dateSeparator}
                            <div className={`flex items-end space-x-2 ${isMyMessage ? 'justify-end' : 'justify-start'}`}>
                                {isMyMessage && <span className="text-xs text-gray-400 mb-1">{formatTime(msg.createdAt)}</span>}
                                <div className={`flex flex-col ${isMyMessage ? 'items-end' : 'items-start'}`}>
                                    {!isMyMessage && <div className="text-xs text-gray-500 mx-1">{msg.sender}</div>}
                                    <div className={`px-3 py-2 rounded-lg max-w-xs ${isMyMessage ? 'bg-blue-500 text-white' : 'bg-gray-200'}`}>
                                        {msg.type === 'FILE_SHARE' ? (
                                            <a href="#"
                                               onClick={(e) => {
                                                   e.preventDefault();
                                                   API.downloadFile(msg.fileId, msg.content)
                                                       .catch(err => alert("다운로드 실패: " + err.message));
                                               }}
                                               className="flex items-center space-x-2 underline">
                                                <img src="/js/images/download_icon.png" alt="download" className="w-4 h-4" />
                                                <span>{msg.content}</span>
                                            </a>
                                        ) : (
                                            msg.content
                                        )}
                                    </div>
                                </div>
                                {!isMyMessage && <span className="text-xs text-gray-400 mb-1">{formatTime(msg.createdAt)}</span>}
                            </div>
                        </React.Fragment>
                    );
                })}

                <div ref={messagesEndRef} />
            </div>
            <form onSubmit={handleSendText} className="p-2 border-t flex items-center">
                <button type="button" onClick={() => fileInputRef.current.click()} className="p-2 text-gray-500 hover:text-gray-700">
                    <img src="/js/images/attach_icon.png" alt="attach" className="w-5 h-5" />
                </button>
                <input type="file" ref={fileInputRef} onChange={handleFileSelect} style={{ display: 'none' }} />
                <input
                    type="text"
                    value={newMessage}
                    onChange={(e) => setNewMessage(e.target.value)}
                    placeholder="메시지 입력..."
                    className="w-full border p-2 rounded"
                />
            </form>
        </div>
    );
};

const InfoPanel = () => (
    <aside className="col-span-3 bg-white p-4 rounded-lg shadow">
        <div className="mb-6">
            <h3 className="font-bold mb-3">※  공지사항</h3>
            <ul className="space-y-2 text-sm text-gray-600">
                <li>• 정기 시스템 점검 안내 (07/20 02:00)</li>
                <li>• 새로운 팀 기능이 업데이트 되었습니다. (05/24 02:00)</li>
                <li>• 광고 문의: admin@abcdef.com</li>
            </ul>
        </div>
        <div>
            <h3 className="font-bold mb-3">※  광고</h3>
            <ul className="space-y-3 text-sm">
                <li>
                    <div>
                        <p className="text-gray-800">`삼다수` 쿠팡 최저가!</p>
                        <p className="text-xs text-gray-400">ㅤ접속하기</p>
                    </div>
                </li>
                <li>
                    <div>
                        <p className="text-gray-800">`아이폰 12 Pro` 전국 최저가 판매!</p>
                        <p className="text-xs text-gray-400">ㅤ접속하기</p>
                    </div>
                </li>
            </ul>
        </div>
    </aside>
);
